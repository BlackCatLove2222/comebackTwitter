package app.morphe.extension.newx.misc;

import android.content.Context;

import java.io.IOException;

import app.morphe.extension.crimera.downloader.engine.DestinationWriter;
import app.morphe.extension.crimera.downloader.engine.Reservation;
import app.morphe.extension.crimera.downloader.model.DownloadRequest;

/**
 * jp24.3: routes each download to the SAF writer when the request targets a
 * user-picked folder tree, or to the MediaStore writer when it targets the
 * built-in default collection.
 */
public final class JpRoutingDestination implements DestinationWriter {

    private final DestinationWriter saf;
    private final DestinationWriter mediaStore;

    public JpRoutingDestination(DestinationWriter saf, DestinationWriter mediaStore) {
        this.saf = saf;
        this.mediaStore = mediaStore;
    }

    @Override
    public Reservation reserve(Context context, DownloadRequest request) throws IOException {
        if (JpMediaStoreDestination.isMediaStoreCollection(request.destinationTree())) {
            return mediaStore.reserve(context, request);
        }
        return saf.reserve(context, request);
    }

    @Override
    public void discard(Context context, Reservation reservation) {
        if (reservation != null
                && JpMediaStoreDestination.isMediaStoreItem(reservation.documentUri())) {
            mediaStore.discard(context, reservation);
        } else {
            saf.discard(context, reservation);
        }
    }
}
