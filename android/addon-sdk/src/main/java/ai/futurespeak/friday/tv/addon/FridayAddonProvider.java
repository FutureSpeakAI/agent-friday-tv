package ai.futurespeak.friday.tv.addon;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * The add-on's own provider (authority {@code <applicationId>.friday}, exported). Friday calls
 * {@link Contract#M_MATCH} with a spoken request whose words include one of the add-on's
 * triggers; the add-on answers whether it is its to handle. Only the core app gets an answer.
 */
public abstract class FridayAddonProvider extends ContentProvider {

    /** Extras to open with (an empty Bundle is fine) when the request is this add-on's, or null. Must be quick. */
    protected abstract Bundle match(String utterance);

    @Override public boolean onCreate() { return true; }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!Contract.CORE_PACKAGE.equals(getCallingPackage())) throw new SecurityException("only Friday may ask");
        Bundle out = new Bundle();
        if (Contract.M_MATCH.equals(method) && arg != null && arg.length() <= 500) {
            Bundle m = match(arg);
            out.putBoolean(Contract.K_MATCHED, m != null);
            if (m != null) out.putBundle(Contract.EXTRA_MATCH, m);
        }
        return out;
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
