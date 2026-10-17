package org.openshell.terminal;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Observes OSC 7 without removing or altering any terminal output. */
public final class DirectoryTracker {
    private static final int MAX_SEQUENCE = 8192;
    private final Consumer<String> listener;
    private final StringBuilder sequence = new StringBuilder();
    private int state;
    private String directory;

    public DirectoryTracker(Consumer<String> listener) { this.listener = listener; }

    public void accept(char[] chars, int offset, int count) {
        for (int i = offset; i < offset + count; i++) accept(chars[i]);
    }

    private void accept(char value) {
        switch (state) {
            case 0 -> { if (value == '\u001b') state = 1; }
            case 1 -> {
                if (value == ']') { sequence.setLength(0); state = 2; }
                else state = value == '\u001b' ? 1 : 0;
            }
            case 2 -> {
                if (value == '\u0007') finish();
                else if (value == '\u001b') state = 3;
                else if (sequence.length() >= MAX_SEQUENCE) { sequence.setLength(0); state = 0; }
                else sequence.append(value);
            }
            case 3 -> {
                if (value == '\\') finish();
                else { sequence.setLength(0); state = value == ']' ? 2 : value == '\u001b' ? 1 : 0; }
            }
            default -> state = 0;
        }
    }

    private void finish() {
        state = 0;
        String text = sequence.toString(); sequence.setLength(0);
        if (!text.startsWith("7;")) return;
        String path = decodePath(text.substring(2));
        if (path != null && !path.equals(directory)) {
            directory = path;
            listener.accept(path);
        }
    }

    public static String decodePath(String value) {
        try {
            URI uri = new URI(value);
            if (!"file".equalsIgnoreCase(uri.getScheme()) || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getRawUserInfo() != null || uri.getPort() != -1) return null;
            String raw = uri.getRawPath();
            if (raw == null || !raw.startsWith("/")) return null;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int i = 0; i < raw.length();) {
                if (raw.charAt(i) == '%') {
                    bytes.write(Integer.parseInt(raw.substring(i + 1, i + 3), 16)); i += 3;
                } else {
                    int point = raw.codePointAt(i);
                    bytes.writeBytes(new String(Character.toChars(point)).getBytes(StandardCharsets.UTF_8));
                    i += Character.charCount(point);
                }
            }
            String path = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            if (path.codePoints().anyMatch(Character::isISOControl)) return null;
            return path;
        } catch (Exception ignored) { return null; }
    }

    /** Bash setup is staged for user review; applies only to the current interactive shell. */
    public static String bashIntegrationCommand() {
        return "if [ -n \"${BASH_VERSION-}\" ]; then if ! declare -F __openshell_report_cwd >/dev/null; then "
                + "__openshell_report_cwd() { local s=$? LC_ALL=C p=\"$PWD\" e= c h i; "
                + "for ((i=0;i<${#p};i++)); do c=${p:i:1}; case \"$c\" in [a-zA-Z0-9/._~-]) e+=\"$c\";; "
                + "*) printf -v h '%%%02X' \"'$c\"; e+=\"$h\";; esac; done; printf '\\033]7;file://%s\\007' \"$e\"; return \"$s\"; }; "
                + "if [[ $(declare -p PROMPT_COMMAND 2>/dev/null) == declare\\ -*a*\\ PROMPT_COMMAND=* ]]; then "
                + "PROMPT_COMMAND+=(__openshell_report_cwd); else PROMPT_COMMAND=\"${PROMPT_COMMAND-}\"$'\\n''__openshell_report_cwd'; fi; "
                + "fi; __openshell_report_cwd; else printf '%s\\n' 'OpenShell: directory integration currently requires Bash'; fi";
    }
}
