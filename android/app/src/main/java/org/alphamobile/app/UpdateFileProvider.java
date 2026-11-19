package org.alphamobile.app;

/** Only the app-private updates directory is exposed, through temporary read grants. */
public final class UpdateFileProvider extends androidx.core.content.FileProvider {
    public UpdateFileProvider(){super();}
}
