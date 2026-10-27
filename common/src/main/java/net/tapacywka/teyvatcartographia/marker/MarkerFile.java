package net.tapacywka.teyvatcartographia.marker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The pin export file: {@code {"format": "teyvatcartographia:pins", "version": 1, "markers": [...]}}, pins written by
 * {@link Marker#CODEC} like in {@code markers.json}. Reading takes any file with a {@code markers} list, so the
 * {@code markers.json} of another world can be imported as it is.
 */
public final class MarkerFile {
    private MarkerFile() {}

    public static final String FORMAT = "teyvatcartographia:pins";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private record Contents(List<Marker> markers) {
        static final Codec<Contents> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("format", FORMAT).forGetter(c -> FORMAT),
                Codec.INT.optionalFieldOf("version", 1).forGetter(c -> 1),
                Marker.CODEC.listOf().fieldOf("markers").forGetter(Contents::markers)
        ).apply(i, (format, version, markers) -> new Contents(markers)));
    }

    public static void write(Path file, List<Marker> markers) throws IOException {
        var root = Contents.CODEC.encodeStart(JsonOps.INSTANCE, new Contents(markers)).getOrThrow(IOException::new);
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(root, w);
        }
    }

    /** The pins of the file; a broken pin is left out. Throws if the file is not a pin file at all. */
    public static List<Marker> read(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return Contents.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(r))
                    .resultOrPartial(e -> {}).map(Contents::markers)
                    .orElseThrow(() -> new IOException("No pins in " + file));
        } catch (RuntimeException e) {
            throw new IOException("Not a pin file: " + file, e);
        }
    }
}
