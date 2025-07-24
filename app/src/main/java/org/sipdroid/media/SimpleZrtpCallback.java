package org.sipdroid.media;


import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import org.sipdroid.sipua.R;

import java.security.AccessControlContext;
import java.util.EnumSet;

import gnu.java.zrtp.ZrtpCodes;
import gnu.java.zrtp.ZrtpUserCallback;

public class SimpleZrtpCallback extends ZrtpUserCallback {

    private Context context = null;


    public SimpleZrtpCallback(Context context) {
        this.context = context;
        //this.mainHandler = new Handler(Looper.getMainLooper());
    }


    @Override
    public void showSAS(final String sas, final boolean verified) {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle("Secure Call Verification");
            builder.setMessage("SAS: " + sas + "\nVerified: " + verified);
            builder.setPositiveButton("OK", null);
            builder.show();
        });
    }


   public Context getContext(){
       return context;
   }

    public void setContext(Context ctx){
        this.context = ctx;
    }

    @Override
    public void secureOn(String cipher) {
        //System.out.println("ZRTP: Secure with cipher " + cipher);
        Log.i("ZRTP", "ZRTP is now secure using: " + cipher);
    }

    /*
    @Override
    public void showSAS(String sas, boolean verified) {
        //System.out.println("ZRTP SAS: " + sas + " (verified=" + verified + ")");
        Log.i("ZRTP", "SAS: " + sas + " verified: " + verified);
        Handler uiHandler = new Handler(Looper.getMainLooper());
        uiHandler.post(() -> {
            TextView sasView = ((Activity)this.getContext()).findViewById(R.id.sasText);
            sasView.setVisibility(View.VISIBLE);
            sasView.setText("SAS: " + sas + (verified ? " (✓)" : " (non vérifié)"));
            if (sasView != null) {
                sasView.setText("SAS: " + sas + (verified ? " (✓)" : " (unverified)"));
            }
        });
    }
    */

    @Override
    public void showMessage(String message) {
        System.out.println("ZRTP MSG: " + message);
    }

    @Override
    public boolean sendDataZRTP(byte[] data) {
        // This must send raw ZRTP data over the RTP socket.
        // For now, just log — the actual send should be done by the outer class (RtpSender):
        System.out.println("ZRTP SendData (length " + data.length + ")");

        // TODO: Forward this to the RTP socket if needed
        return false;
    }

    @Override
    public int activateTimer(int time) {
        return 0;
    }

    @Override
    public int cancelTimer() {
        return 0;
    }

    @Override
    public void sendInfo(ZrtpCodes.MessageSeverity severity, EnumSet<?> subCode) {

    }

    /*
    @Override
    public boolean srtpSecretsReady(ZrtpSrtpSecrets secrets, ZrtpCallback.EnableSecurity part) {
        return false;
    }

    @Override
    public boolean srtpSecretsReady(ZrtpSrtpSecrets secrets, ZrtpCallback.EnableSecurity part) {
        return false;
    }

    @Override
    public void srtpSecretsOff(ZrtpCallback.EnableSecurity part) {

    }
    */
    @Override
    public void srtpSecretsOn(String c, String s, boolean verified) {

    }

    @Override
    public void handleGoClear() {

    }

    @Override
    public void zrtpNegotiationFailed(ZrtpCodes.MessageSeverity severity, EnumSet<?> subCode) {

    }

    @Override
    public void zrtpNegotiationFailed(String reason) {
        System.out.println("ZRTP negotiation failed: " + reason);
    }

    @Override
    public void zrtpNotSuppOther() {
        System.out.println("ZRTP not supported by other client.");
    }

    @Override
    public void zrtpAskEnrollment(ZrtpCodes.InfoEnrollment info) {

    }

    @Override
    public void zrtpInformEnrollment(ZrtpCodes.InfoEnrollment info) {

    }

    @Override
    public void signSAS(byte[] sasHash) {

    }

    @Override
    public boolean checkSASSignature(byte[] sasHash) {
        return false;
    }

    @Override
    public void zrtpAskEnrollment(String info) {
        System.out.println("ZRTP asks for enrollment: " + info);
    }

    @Override
    public void zrtpInformEnrollment(String info) {
        System.out.println("ZRTP informs about enrollment: " + info);
    }

    @Override
    public void zrtpSignSAS(byte[] sasHash) {
        System.out.println("ZRTP requests SAS to be signed. Hash: " + bytesToHex(sasHash));
    }

    @Override
    public boolean zrtpSASSigned(byte[] sasHash) {
        System.out.println("ZRTP checking SAS signature: " + bytesToHex(sasHash));
        return true; // accept as valid for now
    }

    private static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data)
            sb.append(String.format("%02X", b));
        return sb.toString();
    }
}
