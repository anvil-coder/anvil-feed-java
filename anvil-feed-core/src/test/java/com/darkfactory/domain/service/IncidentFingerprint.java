package com.darkfactory.domain.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * THE ORACLE — a verbatim copy of the SERVER's grouping rule. TEST SCOPE ONLY; it never ships.
 *
 * <p>Copied from {@code dark-factory}, worktree {@code /tmp/wt-incidents}, file
 * {@code domain/src/main/java/com/darkfactory/domain/service/IncidentFingerprint.java} (2026-09-17) — the
 * implementation the ingest actually runs. The package and the class name are kept so that a reviewer can
 * diff this file against that one directly.
 *
 * <p>Exactly four edits were made, all mechanical, none of them touching the algorithm:
 * <ol>
 *   <li>{@code IncidentDelivery.Frame} is not on this module's classpath, so the three members the rule
 *       reads are declared below as a nested {@link Frame} record;</li>
 *   <li>{@code SecretFingerprint.sha256Hex} is not either, so its three-line body is inlined;</li>
 *   <li>{@code normalizeFile} is {@code public} rather than package-private, so the test in
 *       {@code tech.anvilcoder.feed} can call it, and {@code payload()} was added beside {@code of()} so
 *       that a mismatch can be read rather than guessed at;</li>
 *   <li>the server's javadoc prose is replaced by this header — the reasoning lives in
 *       {@code tech.anvilcoder.feed.Fingerprint}, and two copies of it would be edited apart.</li>
 * </ol>
 *
 * <p>Why an oracle at all: {@code conformance/fingerprint/vectors.json} pins fifteen inputs, and fifteen
 * inputs are a sample. This is the JVM and the server is the JVM, so the port can be held against the
 * original on ANY input — which is what {@code FingerprintTest} does, on the vectors and on ten thousand
 * generated ones. A vector file can only ever prove the cases somebody thought of.
 */
public final class IncidentFingerprint {

    /** One stack frame: the three members {@code IncidentDelivery.Frame} exposes to this rule. */
    public record Frame(String file, String function, Boolean inApp) { }

    public static final int VERSION = 1;

    private static final int TOP_FRAMES = 3;

    private static final Pattern SCHEME_AND_HOST = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://[^/]*");

    private static final Pattern VERSION_SEGMENT = Pattern.compile("v?\\d+\\.\\d+(?:\\.\\d+)?");

    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[A-Za-z]:");

    private static final Pattern CONTENT_HASH = Pattern.compile("[-.][0-9a-f]{4,}(?=\\.[A-Za-z0-9]+$)");

    private IncidentFingerprint() {
    }

    public static String of(String service, String exceptionType, String title, List<Frame> frames) {
        return sha256Hex(payload(service, exceptionType, title, frames));
    }

    public static String payload(String service, String exceptionType, String title, List<Frame> frames) {
        List<String> top = topFrames(frames);
        StringBuilder payload = new StringBuilder("incident-fingerprint/v").append(VERSION);
        field(payload, top.isEmpty() ? "title" : "frames");
        field(payload, text(service));
        field(payload, text(exceptionType));
        if (top.isEmpty()) {
            field(payload, text(title));
        } else {
            top.forEach(frame -> field(payload, frame));
        }
        return payload.toString();
    }

    private static void field(StringBuilder payload, String value) {
        payload.append('\u001F').append(value.length()).append(':').append(value);
    }

    private static List<String> topFrames(List<Frame> frames) {
        if (frames == null || frames.isEmpty()) {
            return List.of();
        }
        List<Frame> inApp = new ArrayList<>();
        List<Frame> rest = new ArrayList<>();
        for (Frame frame : frames) {
            if (frame == null) {
                continue;
            }
            (Boolean.TRUE.equals(frame.inApp()) ? inApp : rest).add(frame);
        }
        inApp.addAll(rest);
        return inApp.stream()
                .limit(TOP_FRAMES)
                .map(frame -> normalizeFile(frame.file()) + "#" + text(frame.function()))
                .toList();
    }

    public static String normalizeFile(String file) {
        if (file == null || file.isBlank()) {
            return "";
        }
        String path = file.trim().replace('\\', '/');

        String withoutHost = SCHEME_AND_HOST.matcher(path).replaceFirst("");
        boolean deployArtefact = !withoutHost.equals(path);
        path = WINDOWS_DRIVE.matcher(withoutHost).replaceFirst("");
        path = cutAt(cutAt(path, '?'), '#');

        if (deployArtefact || CONTENT_HASH.matcher(path).find()) {
            path = withoutVersionSegment(path);
        }
        path = CONTENT_HASH.matcher(path).replaceFirst("");
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path;
    }

    private static String withoutVersionSegment(String path) {
        List<String> segments = new ArrayList<>(Arrays.asList(path.split("/", -1)));
        int found = -1;
        for (int i = 0; i < segments.size() - 1; i++) {   // never the file name itself
            if (VERSION_SEGMENT.matcher(segments.get(i)).matches()) {
                if (found >= 0) {
                    return path;
                }
                found = i;
            }
        }
        if (found < 0) {
            return path;
        }
        segments.remove(found);
        return String.join("/", segments);
    }

    private static String cutAt(String value, char marker) {
        int at = value.indexOf(marker);
        return at < 0 ? value : value.substring(0, at);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    /** {@code SecretFingerprint.sha256Hex}, inlined. */
    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException broken) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", broken);
        }
    }
}
