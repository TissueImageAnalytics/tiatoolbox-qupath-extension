package qupath.ext.tiatoolbox.ui;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.StringProperty;
import qupath.lib.gui.prefs.PathPrefs;

/** Persistent extension preferences, backed by QuPath's {@link PathPrefs} store. */
public final class TIAPrefs {

    public static final StringProperty device =
            PathPrefs.createPersistentPreference("tiatoolbox.device", "cpu");

    public static final IntegerProperty batchSize =
            PathPrefs.createPersistentPreference("tiatoolbox.batchSize", 8);

    private TIAPrefs() {}
}
