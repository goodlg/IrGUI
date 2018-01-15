package org.ftd.gyn;

/**
 * Created by sdduser on 18-1-10.
 */

public interface DataCallback {
    void onDataReceived(Object obj, int mode, byte[] data);
}
