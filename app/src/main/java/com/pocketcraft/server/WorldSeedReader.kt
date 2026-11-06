package com.pocketcraft.server

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Reads ONLY the numeric seed from Java Edition's compressed NBT save files.
 *
 * Minecraft 26.1+ moved WorldGenSettings from level.dat to
 * data/minecraft/world_gen_settings.dat. Older worlds use level.dat.
 * Refuse to guess when the seed cannot be verified.
 */
internal object WorldSeedReader {
    private const val MAX_FILE_BYTES = 16L * 1024 * 1024
    private const val MAX_INFLATED_BYTES = 64L * 1024 * 1024
    private const val MAX_TAGS = 200_000
    private const val MAX_DEPTH = 64
    private const val MAX_NAME = 4096

    fun readSeed(world: File): Long {
        val modern = File(world, "data/minecraft/world_gen_settings.dat")
        val legacy = File(world, "level.dat")
        val selected = if (modern.isFile) modern else legacy
        if (!selected.isFile) throw IOException("No world-generation seed file found. Start this world once first.")
        return readFile(selected)
    }

    internal fun readFile(file: File): Long {
        if (!file.isFile || file.length() !in 1..MAX_FILE_BYTES)
            throw IOException("World generation file is missing or exceeds the 16 MB safety limit.")
        val first = file.inputStream().buffered().use {
            val a = it.read()
            val b = it.read()
            a == 0x1f && b == 0x8b
        }
        val matches = mutableListOf<Pair<List<String>, Long>>()
        val raw: InputStream = BufferedInputStream(file.inputStream())
        val decoded: InputStream = if (first) GZIPInputStream(raw) else raw
        try {
            DataInputStream(Limit(decoded, MAX_INFLATED_BYTES)).use { input ->
                val rootType = input.readUnsignedByte()
                if (rootType != 10) throw IOException("Not a Java NBT compound.")
                readName(input)
                val parser = Parser(input, matches)
                parser.compound(emptyList(), 0)
            }
        } catch (e: IOException) {
            throw IOException("Cannot read original world seed: " + (e.message ?: "Corrupt NBT file"), e)
        }
        fun normalized(parts: List<String>) = parts.map {
            it.lowercase(java.util.Locale.ROOT).replace("_", "")
        }
        val preferred = listOf(
            listOf("data", "seed"),
            listOf("data", "worldgensettings", "seed"),
            listOf("worldgensettings", "seed"),
            listOf("seed"),
            listOf("data", "worldoptions", "seed"),
            listOf("worldoptions", "seed"),
            listOf("data", "randomseed")
        )
        for (path in preferred) {
            matches.firstOrNull { normalized(it.first) == path }?.let { return it.second }
        }
        throw IOException("The original seed is not available in this world's NBT files. No world was reset.")
    }

    private fun readName(input: DataInputStream): String {
        val length = input.readUnsignedShort()
        if (length > MAX_NAME) throw IOException("NBT tag name is unusually long.")
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private class Limit(input: InputStream, private val maximum: Long): FilterInputStream(input) {
        private var used = 0L
        private fun record(count: Int) {
            if (count > 0) {
                used += count
                if (used > maximum) throw IOException("NBT decompression exceeds safety limit.")
            }
        }
        override fun read(): Int = super.read().also { if (it >= 0) record(1) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            `in`.read(buffer, offset, length).also { record(it) }
        override fun skip(n: Long): Long {
            // Always consume through read() to enforce the byte limit.
            var remain = n
            val scratch = ByteArray(8192)
            while (remain > 0) {
                val take = minOf(scratch.size.toLong(), remain).toInt()
                val got = read(scratch, 0, take)
                if (got < 0) throw IOException("Truncated NBT data")
                remain -= got
            }
            return n
        }
    }

    private class Parser(
        private val data: DataInputStream,
        private val hits: MutableList<Pair<List<String>, Long>>
    ) {
        private var tags = 0
        private fun count(depth: Int) {
            if (++tags > MAX_TAGS || depth > MAX_DEPTH)
                throw IOException("NBT nesting or entry count exceeds safety limit.")
        }
        private fun length(): Int {
            val n = data.readInt()
            if (n < 0 || n > 16_777_216) throw IOException("Invalid NBT array length.")
            return n
        }
        private fun skip(n: Long) {
            if (n < 0 || n > MAX_INFLATED_BYTES) throw IOException("NBT value too large.")
            var remain = n
            val buf = ByteArray(8192)
            while (remain > 0) {
                val got = data.read(buf, 0, minOf(buf.size.toLong(), remain).toInt())
                if (got < 0) throw IOException("Unexpected end of NBT stream")
                remain -= got
            }
        }
        fun compound(path: List<String>, depth: Int) {
            count(depth)
            while (true) {
                val type = data.readUnsignedByte()
                if (type == 0) break
                val name = readName(data)
                value(type, path + name, depth + 1)
            }
        }
        private fun value(type: Int, path: List<String>, depth: Int) {
            count(depth)
            when (type) {
                1 -> data.readByte()
                2 -> data.readShort()
                3 -> {
                    val v = data.readInt()
                    if (path.lastOrNull()?.equals("seed", true) == true ||
                        path.lastOrNull()?.equals("RandomSeed", true) == true) hits.add(path to v.toLong())
                }
                4 -> {
                    val v = data.readLong()
                    if (path.lastOrNull()?.equals("seed", true) == true ||
                        path.lastOrNull()?.equals("RandomSeed", true) == true) hits.add(path to v)
                }
                5 -> data.readFloat()
                6 -> data.readDouble()
                7 -> skip(length().toLong())
                8 -> skip(data.readUnsignedShort().toLong())
                9 -> {
                    val item = data.readUnsignedByte()
                    val size = length()
                    if (item == 0 && size > 0) throw IOException("Invalid NBT list.")
                    repeat(size) { value(item, path + "[]", depth + 1) }
                }
                10 -> compound(path, depth + 1)
                11 -> skip(length().toLong() * 4)
                12 -> skip(length().toLong() * 8)
                else -> throw IOException("Unsupported NBT type " + type)
            }
        }
    }
}
