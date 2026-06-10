package qupath.ext.tiatoolbox.install;

import java.nio.file.Path;
import java.util.Locale;

/** Options controlling how the bundled Python runtime is installed. */
public record RuntimeInstallOptions(
        Path localTiatoolboxClone,
        boolean editableLocalClone,
        String remoteTiatoolboxGitUrl,
        String remoteTiatoolboxGitBranch
) {

    public RuntimeInstallOptions(Path localTiatoolboxClone, boolean editableLocalClone) {
        this(localTiatoolboxClone, editableLocalClone, null, null);
    }

    public RuntimeInstallOptions {
        if (localTiatoolboxClone != null) {
            localTiatoolboxClone = localTiatoolboxClone.toAbsolutePath().normalize();
        }
        if (remoteTiatoolboxGitUrl != null) {
            remoteTiatoolboxGitUrl = remoteTiatoolboxGitUrl.trim();
            if (remoteTiatoolboxGitUrl.isBlank()) {
                remoteTiatoolboxGitUrl = null;
            }
        }
        if (remoteTiatoolboxGitBranch != null) {
            remoteTiatoolboxGitBranch = remoteTiatoolboxGitBranch.trim();
            if (remoteTiatoolboxGitBranch.isBlank()) {
                remoteTiatoolboxGitBranch = null;
            }
        }
        if (localTiatoolboxClone != null && remoteTiatoolboxGitUrl != null) {
            throw new IllegalArgumentException("Specify either a local clone or a git URL, not both.");
        }
        if (localTiatoolboxClone == null && remoteTiatoolboxGitUrl == null) {
            editableLocalClone = false;
        }
    }

    public static RuntimeInstallOptions defaultInstall() {
        return new RuntimeInstallOptions(null, false, null, null);
    }

    public static RuntimeInstallOptions fromLocalClone(Path localTiatoolboxClone) {
        return new RuntimeInstallOptions(localTiatoolboxClone, true);
    }

    public static RuntimeInstallOptions fromTiatoolboxSource(String source, boolean editable) {
        var text = source == null ? "" : source.trim();
        if (text.isBlank()) {
            return defaultInstall();
        }
        if (looksLikeGitUrl(text)) {
            var git = parseGitSource(text);
            return new RuntimeInstallOptions(null, editable, git.url(), git.branch());
        }
        return new RuntimeInstallOptions(Path.of(text), editable);
    }

    public boolean useLocalTiatoolboxClone() {
        return localTiatoolboxClone != null;
    }

    public boolean useRemoteTiatoolboxClone() {
        return remoteTiatoolboxGitUrl != null;
    }

    public boolean useCustomTiatoolboxSource() {
        return useLocalTiatoolboxClone() || useRemoteTiatoolboxClone();
    }

    private static boolean looksLikeGitUrl(String text) {
        var lower = text.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("ssh://")
                || lower.startsWith("git://")
                || lower.startsWith("file://")
                || text.matches("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+:.+");
    }

    private static GitSource parseGitSource(String text) {
        var hash = text.lastIndexOf('#');
        if (hash < 0) {
            return new GitSource(text, null);
        }
        var url = text.substring(0, hash).trim();
        var branch = text.substring(hash + 1).trim();
        if (url.isBlank() || branch.isBlank()) {
            throw new IllegalArgumentException("Git source must be URL or URL#branch.");
        }
        return new GitSource(url, branch);
    }

    private record GitSource(String url, String branch) {}
}
