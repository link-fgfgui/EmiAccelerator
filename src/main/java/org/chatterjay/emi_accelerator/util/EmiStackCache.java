package org.chatterjay.emi_accelerator.util;

import com.mojang.logging.LogUtils;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.registry.EmiStackList;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.fml.ModList;
import org.chatterjay.emi_accelerator.config.ModConfig;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class EmiStackCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CACHE_VERSION = 4;
    private static final int MAGIC = 0x454D4943; // "EMIC"

    private static final LZ4Factory LZ4 = LZ4Factory.safeInstance();
    private static final LZ4FastDecompressor DECOMPRESSOR = LZ4.fastDecompressor();
    private static final LZ4Compressor COMPRESSOR = LZ4.fastCompressor();

    private static final byte TYPE_ITEM = 0;
    private static final byte TYPE_FLUID = 1;

    private static final StreamCodec<ByteBuf, CacheEntry> ENTRY_CODEC = StreamCodec.of(
            (buf, e) -> {
                buf.writeByte(e.type);
                ResourceLocation.STREAM_CODEC.encode(buf, e.id);
                if (e.type == TYPE_ITEM) {
                    ByteBufCodecs.OPTIONAL_COMPOUND_TAG.encode(buf, Optional.ofNullable(e.nbt));
                }
            },
            buf -> {
                byte type = buf.readByte();
                ResourceLocation id = ResourceLocation.STREAM_CODEC.decode(buf);
                CompoundTag nbt = type == TYPE_ITEM
                        ? ByteBufCodecs.OPTIONAL_COMPOUND_TAG.decode(buf).orElse(null)
                        : null;
                return new CacheEntry(type, id, nbt);
            }
    );

    private record CacheEntry(byte type, ResourceLocation id, @Nullable CompoundTag nbt) {}

    private static volatile boolean cacheUsed = false;
    private static volatile long lastLoadTime = 0;

    private static Path getCacheFile() {
        return ModConfig.getConfigDir().resolve("stack-cache.emic");
    }

    public static boolean tryLoad() {
        if (!ModConfig.isCacheEnabled()) return false;

        cacheUsed = false;
        lastLoadTime = 0;

        cleanOldJsonCache();

        var cacheFile = getCacheFile();
        if (!Files.exists(cacheFile)) return false;

        try {
            if (Files.size(cacheFile) > (long) ModConfig.getMaxFileSizeMb() * 1024 * 1024) {
                LOGGER.warn("Cache file exceeds size limit, deleting");
                Files.deleteIfExists(cacheFile);
                return false;
            }
        } catch (IOException e) {
            return false;
        }

        long start = System.currentTimeMillis();

        try {
            byte[] fileBytes = Files.readAllBytes(cacheFile);
            if (fileBytes.length < 8) {
                Files.deleteIfExists(cacheFile);
                return false;
            }

            int magic = ((fileBytes[0] & 0xFF) << 24) | ((fileBytes[1] & 0xFF) << 16) | ((fileBytes[2] & 0xFF) << 8) | (fileBytes[3] & 0xFF);
            if (magic != MAGIC) {
                Files.deleteIfExists(cacheFile);
                return false;
            }

            int uncompressedLen = ((fileBytes[4] & 0xFF) << 24) | ((fileBytes[5] & 0xFF) << 16) | ((fileBytes[6] & 0xFF) << 8) | (fileBytes[7] & 0xFF);
            if (uncompressedLen <= 0) {
                Files.deleteIfExists(cacheFile);
                return false;
            }

            byte[] compressed = new byte[fileBytes.length - 8];
            System.arraycopy(fileBytes, 8, compressed, 0, compressed.length);

            byte[] payload;
            try {
                payload = DECOMPRESSOR.decompress(compressed, 0, uncompressedLen);
            } catch (Exception e) {
                LOGGER.debug("LZ4 decompression failed, cache may be corrupted");
                Files.deleteIfExists(cacheFile);
                return false;
            }

            if (payload.length < 40) {
                Files.deleteIfExists(cacheFile);
                return false;
            }

            ByteBuf buf = Unpooled.wrappedBuffer(payload);
            try {
                int version = buf.readInt();
                if (version != CACHE_VERSION) {
                    Files.deleteIfExists(cacheFile);
                    return false;
                }

                byte[] storedHash = new byte[32];
                buf.readBytes(storedHash);
                byte[] computedHash = computeModHashBytes();
                if (!MessageDigest.isEqual(storedHash, computedHash)) {
                    if (ModConfig.isAutoClearOnModChange()) {
                        Files.deleteIfExists(cacheFile);
                    }
                    return false;
                }

                int count = buf.readInt();
                List<EmiStack> builtStacks = new ArrayList<>(count);

                for (int i = 0; i < count; i++) {
                    try {
                        CacheEntry entry = ENTRY_CODEC.decode(buf);
                        if (entry.type() == TYPE_ITEM) {
                            Item item = BuiltInRegistries.ITEM.get(entry.id());
                            if (item == null || item == BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace("air")))
                                continue;

                            if (entry.nbt() != null) {
                                RegistryAccess registry = getRegistryAccess();
                                if (registry != null) {
                                    ItemStack parsed = ItemStack.parse(registry, entry.nbt()).orElse(null);
                                    if (parsed != null && !parsed.isEmpty()) {
                                        builtStacks.add(EmiStack.of(parsed));
                                    } else {
                                        builtStacks.add(EmiStack.of(item));
                                    }
                                } else {
                                    builtStacks.add(EmiStack.of(item));
                                }
                            } else {
                                builtStacks.add(EmiStack.of(item));
                            }
                        } else if (entry.type() == TYPE_FLUID) {
                            Fluid fluid = BuiltInRegistries.FLUID.get(entry.id());
                            if (fluid != null) {
                                builtStacks.add(EmiStack.of(fluid));
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.debug("Failed to deserialize cache entry: {}", e.getMessage());
                    }
                }

                if (builtStacks.isEmpty()) {
                    Files.deleteIfExists(cacheFile);
                    return false;
                }

                EmiStackList.stacks = builtStacks;
                cacheUsed = true;
                lastLoadTime = System.currentTimeMillis() - start;
                LOGGER.info("[EMI加速] Cache loaded {} stacks in {}ms", builtStacks.size(), lastLoadTime);
                long itemCount = builtStacks.stream().filter(s -> s.getKey() instanceof Item).count();
                long fluidCount = builtStacks.stream().filter(s -> s.getKey() instanceof Fluid).count();
                LOGGER.info("[EMI加速] Cache breakdown: {} items, {} fluids", itemCount, fluidCount);
                if (!builtStacks.isEmpty()) {
                    EmiStack first = builtStacks.get(0);
                    LOGGER.info("[EMI加速] First cached stack: type={}, id={}", first.getKey().getClass().getName(), first.getId());
                }
                return true;
            } finally {
                buf.release();
            }
        } catch (Exception e) {
            try {
                Files.deleteIfExists(cacheFile);
            } catch (IOException ignored) {
            }
            return false;
        }
    }

    public static void saveAsync() {
        if (!ModConfig.isCacheEnabled()) return;
        if (cacheUsed) return;

        CompletableFuture.runAsync(() -> {
            try {
                save();
            } catch (Exception e) {
                LOGGER.error("Failed to save EMI cache", e);
            }
        });
    }

    private static void save() {
        List<EmiStack> stacks = EmiStackList.stacks;
        if (stacks == null || stacks.isEmpty()) {
            LOGGER.info("[EMI加速] Save skipped: stacks is null or empty");
            return;
        }
        LOGGER.info("[EMI加速] Save start: {} stacks in list", stacks.size());

        var cacheFile = getCacheFile();
        try {
            Files.createDirectories(cacheFile.getParent());
        } catch (IOException e) {
            return;
        }

        // Collect entries
        List<CacheEntry> entries = new ArrayList<>(stacks.size());
        for (EmiStack stack : stacks) {
            try {
                Object key = stack.getKey();
                if (key instanceof Item item) {
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                    CompoundTag nbt = null;
                    var changes = stack.getComponentChanges();
                    if (changes != null && !changes.isEmpty()) {
                        RegistryAccess registry = getRegistryAccess();
                        if (registry != null) {
                            Tag tag = stack.getItemStack().save(registry);
                            if (tag instanceof CompoundTag ct) {
                                nbt = ct;
                            }
                        }
                    }
                    entries.add(new CacheEntry(TYPE_ITEM, id, nbt));
                } else if (key instanceof Fluid fluid) {
                    entries.add(new CacheEntry(TYPE_FLUID, BuiltInRegistries.FLUID.getKey(fluid), null));
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to serialize cache entry: {}", e.getMessage());
            }
        }

        // Encode payload and write compressed file
        ByteBuf payloadBuf = Unpooled.buffer();
        try {
            payloadBuf.writeInt(CACHE_VERSION);
            payloadBuf.writeBytes(computeModHashBytes());
            payloadBuf.writeInt(entries.size());
            for (CacheEntry entry : entries) {
                ENTRY_CODEC.encode(payloadBuf, entry);
            }

            byte[] payload = new byte[payloadBuf.readableBytes()];
            payloadBuf.readBytes(payload);

            // LZ4 compress
            int maxCompressedLen = COMPRESSOR.maxCompressedLength(payload.length);
            byte[] compressedBuf = new byte[maxCompressedLen];
            int compressedLen = COMPRESSOR.compress(payload, 0, payload.length, compressedBuf, 0, maxCompressedLen);

            // Write file: magic + uncompressed length + compressed data
            byte[] output = new byte[8 + compressedLen];
            output[0] = (byte) (MAGIC >> 24);
            output[1] = (byte) (MAGIC >> 16);
            output[2] = (byte) (MAGIC >> 8);
            output[3] = (byte) MAGIC;
            output[4] = (byte) (payload.length >> 24);
            output[5] = (byte) (payload.length >> 16);
            output[6] = (byte) (payload.length >> 8);
            output[7] = (byte) payload.length;
            System.arraycopy(compressedBuf, 0, output, 8, compressedLen);

            Files.write(cacheFile, output);
            LOGGER.info("[EMI加速] Cache saved: {} entries, {} bytes ({} compressed)", entries.size(), payload.length, compressedLen);
        } catch (Exception e) {
            LOGGER.error("Failed to write cache file", e);
        } finally {
            payloadBuf.release();
        }
    }

    @Nullable
    private static RegistryAccess getRegistryAccess() {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        return mc.level.registryAccess();
    }

    private static void cleanOldJsonCache() {
        try {
            Path oldFile = ModConfig.getConfigDir().resolve("stack-cache.json");
            Files.deleteIfExists(oldFile);
        } catch (IOException ignored) {
        }
    }

    private static byte[] computeModHashBytes() {
        try {
            var mods = ModList.get().getMods();
            var input = mods.stream()
                    .map(m -> m.getModId() + "=" + m.getVersion())
                    .sorted()
                    .collect(Collectors.joining("\n"));
            var md = MessageDigest.getInstance("SHA-256");
            return md.digest(input.getBytes());
        } catch (Exception e) {
            return new byte[32];
        }
    }

    public static boolean wasCacheUsed() {
        return cacheUsed;
    }

    public static long getLastLoadTime() {
        return lastLoadTime;
    }

    public static boolean cacheFileExists() {
        return Files.exists(getCacheFile());
    }

    public static void clearCache() {
        try {
            Files.deleteIfExists(getCacheFile());
            cacheUsed = false;
        } catch (IOException e) {
            LOGGER.error("Failed to clear cache", e);
        }
    }

    public static long getCacheFileSize() {
        try {
            return Files.size(getCacheFile());
        } catch (IOException e) {
            return -1;
        }
    }
}
