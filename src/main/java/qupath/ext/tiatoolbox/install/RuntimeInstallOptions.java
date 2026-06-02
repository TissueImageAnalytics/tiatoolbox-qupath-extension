package qupath.ext.tiatoolbox.install;

import java.nio.file.Path;

/** Options controlling how the bundled Python runtime is installed. */
public record RuntimeInstallOptions(Path localTiatoolboxClone, boolean editableLocalClone) {

    public RuntimeInstallOptions {
        if (localTiatoolboxClone != null) {
            localTiatoolboxClone = localTiatoolboxClone.toAbsolutePath().normalize();
        } else {
            editableLocalClone = false;
        }
    }

    public static RuntimeInstallOptions defaultInstall() {
        return new RuntimeInstallOptions(null, false);
    }

    public static RuntimeInstallOptions fromLocalClone(Path localTiatoolboxClone) {
        return new RuntimeInstallOptions(localTiatoolboxClone, true);
    }

    public boolean useLocalTiatoolboxClone() {
        return localTiatoolboxClone != null;
    }
}
