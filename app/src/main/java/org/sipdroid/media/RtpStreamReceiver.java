package org.sipdroid.media;

import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import org.sipdroid.net.RtpPacket;
import org.sipdroid.net.RtpSocket;
import org.sipdroid.net.SipdroidSocket;
import org.sipdroid.sipua.R;
import org.sipdroid.sipua.UserAgent;
import org.sipdroid.sipua.ui.InCallScreen;
import org.sipdroid.sipua.ui.Receiver;
import org.sipdroid.sipua.ui.Settings;
import org.sipdroid.sipua.ui.Sipdroid;
import org.sipdroid.codecs.Codecs;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences.Editor;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.ToneGenerator;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.system.ErrnoException;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import gnu.java.zrtp.jmf.transform.RawPacket;
import gnu.java.zrtp.jmf.transform.zrtp.ZRTPTransformEngine;

public class RtpStreamReceiver extends Thread {

	private static final String TAG = "RtpStreamReceiver";
	public static boolean DEBUG = true; // Enable/disable debug logging
	private static final int BUFFER_SIZE = 1024 * 4;
	private static final int SO_TIMEOUT = 1000;

	private Codecs.Map p_type;
	private static String codec = "";
	private RtpSocket rtp_socket;
	private volatile boolean running;
	private AudioManager am;
	private ContentResolver cr;
	public static int speakermode = -1;
	public static boolean bluetoothmode;
	private CallRecorder call_recorder;
	private ToneGenerator tg;
	private int oldvol = -1;
	private static boolean restored = false;
	private AudioTrack track;
	private int maxjitter, minjitter, minjitteradjust;
	private int cnt, cnt2, user, luser, luser2, lserver;
	public static int jitter, mu;
	private double avgheadroom, devheadroom;
	private int avgcnt;
	public static long timeout;
	private long timeoutstart;
	private int seq;
	private PowerManager.WakeLock pwl, pwl2;
	private WifiManager.WifiLock wwl;
	private boolean lockLast, lockFirst;
	private boolean keepon;

	private ZRTPTransformEngine zrtpEngine = null;

	// Synchronization locks
	private final Object socketLock = new Object();
	private final Object stateLock = new Object();

	public RtpStreamReceiver(SipdroidSocket socket, Codecs.Map payload_type, CallRecorder rec) {
		init(socket);
		p_type = payload_type;
		call_recorder = rec;
		if (DEBUG) Log.d(TAG, "Receiver initialized with local port: " + rtp_socket.getDatagramSocket().getLocalPort());
	}

	public RtpStreamReceiver(SipdroidSocket socket, Codecs.Map payload_type, CallRecorder rec, ZRTPTransformEngine zrtpEngine) {
		init(socket);
		p_type = payload_type;
		call_recorder = rec;
		this.zrtpEngine = zrtpEngine;
		if (DEBUG) Log.d(TAG, "Receiver initialized with local port: " + rtp_socket.getDatagramSocket().getLocalPort());
	}

	private void init(SipdroidSocket socket) {
		if (socket != null) {
			rtp_socket = new RtpSocket(socket);
		} else {
			Log.e(TAG, "SipdroidSocket is null, initialization failed");
		}
	}

	public boolean isRunning() {
		return running;
	}

	public void halt() {
		synchronized (stateLock) {
			running = false;
		}
		synchronized (socketLock) {
			if (rtp_socket != null && !rtp_socket.isClosed()) {
				rtp_socket.close();
				if (DEBUG) Log.d(TAG, "RTP socket closed by halt()");
			}
		}
	}

	void bluetooth() {
		speaker(AudioManager.MODE_IN_CALL);
		enableBluetooth(!bluetoothmode);
	}

	static boolean was_enabled;

	static void enableBluetooth(boolean mode) {
		if (bluetoothmode != mode && (!mode || isBluetoothAvailable())) {
			if (mode) was_enabled = true;
			Bluetooth.enable(bluetoothmode = mode);
		}
	}

	void cleanupBluetooth() {
		if (was_enabled && Integer.parseInt(Build.VERSION.SDK) == 8) {
			enableBluetooth(true);
			try {
				Thread.sleep(3000);
			} catch (InterruptedException e) {
				Log.w(TAG, "Interrupted during Bluetooth cleanup", e);
			}
			if (Receiver.call_state == UserAgent.UA_STATE_IDLE) {
				android.os.Process.killProcess(android.os.Process.myPid());
			}
		}
	}

	public static boolean isBluetoothAvailable() {
		if (Receiver.headset > 0 || Receiver.docked > 0) return false;
		if (!isBluetoothSupported()) return false;
		return Bluetooth.isAvailable();
	}

	public static boolean isBluetoothSupported() {
		if (Integer.parseInt(Build.VERSION.SDK) < 8) return false;
		return Bluetooth.isSupported();
	}

	static public boolean notoast;

	public int speaker(int mode) {
		int old = speakermode;
		if ((Receiver.headset > 0 || Receiver.docked > 0 || Receiver.bluetooth > 0) &&
				mode != Receiver.speakermode()) return old;
		if (mode == old) return old;
		enableBluetooth(false);
		saveVolume();
		setMode(speakermode = mode);
		setCodec();
		restoreVolume();
		if (notoast) {
			notoast = false;
			return old;
		}
		if (mode == AudioManager.MODE_NORMAL && Thread.currentThread().getName().equals("main")) {
			Toast.makeText(Receiver.mContext, R.string.help_speakerphone, Toast.LENGTH_LONG).show();
		}
		return old;
	}

	static ToneGenerator ringbackPlayer;
	static int oldvolRingback = -1;

	static int stream() {
		return speakermode == AudioManager.MODE_IN_CALL ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC;
	}

	public static synchronized void ringback(boolean ringback) {
		if (ringback && ringbackPlayer == null) {
			ringbackPlayer = new ToneGenerator(stream(), (int) (ToneGenerator.MAX_VOLUME * 2 * org.sipdroid.sipua.ui.Settings.getEarGain()));
			AudioManager am = (AudioManager) Receiver.mContext.getSystemService(Context.AUDIO_SERVICE);
			oldvolRingback = am.getStreamVolume(AudioManager.STREAM_MUSIC);
			setMode(speakermode);
			enableBluetooth(PreferenceManager.getDefaultSharedPreferences(Receiver.mContext)
					.getBoolean(org.sipdroid.sipua.ui.Settings.PREF_BLUETOOTH, org.sipdroid.sipua.ui.Settings.DEFAULT_BLUETOOTH));
			am.setStreamVolume(stream(),
					PreferenceManager.getDefaultSharedPreferences(Receiver.mContext).getInt("volume" + speakermode,
							am.getStreamMaxVolume(stream()) * (speakermode == AudioManager.MODE_NORMAL ? 4 : 3) / 4), 0);
			ringbackPlayer.startTone(ToneGenerator.TONE_SUP_RINGTONE);
		} else if (!ringback && ringbackPlayer != null) {
			ringbackPlayer.stopTone();
			ringbackPlayer.release();
			ringbackPlayer = null;
			if (Receiver.call_state == UserAgent.UA_STATE_IDLE) {
				AudioManager am = (AudioManager) Receiver.mContext.getSystemService(Context.AUDIO_SERVICE);
				restoreMode();
				enableBluetooth(false);
				am.setStreamVolume(AudioManager.STREAM_MUSIC, oldvolRingback, 0);
				oldvolRingback = -1;
			}
		}
	}

	double smin = 200, s;
	public static int nearend;

	void calc(short[] lin, int off, int len) {
		int i, j;
		double sm = 30000, r;
		for (i = 0; i < len; i += 5) {
			j = lin[i + off];
			s = 0.03 * Math.abs(j) + 0.97 * s;
			if (s < sm) sm = s;
			if (s > smin) nearend = 6000 * mu / 5;
			else if (nearend > 0) nearend--;
		}
		for (i = 0; i < len; i++) {
			j = lin[i + off];
			if (j > 6550) lin[i + off] = 6550 * 5;
			else if (j < -6550) lin[i + off] = -6550 * 5;
			else lin[i + off] = (short) (j * 5);
		}
		r = (double) len / (100000 * mu);
		if (sm > 2 * smin || sm < smin / 2) smin = sm * r + smin * (1 - r);
	}

	void calc2(short[] lin, int off, int len) {
		int i, j;
		for (i = 0; i < len; i++) {
			j = lin[i + off];
			if (j > 16350) lin[i + off] = 16350 << 1;
			else if (j < -16350) lin[i + off] = -16350 << 1;
			else lin[i + off] = (short) (j << 1);
		}
	}

	static long down_time;

	public static void adjust(int keyCode, boolean down, boolean show) {
		AudioManager mAudioManager = (AudioManager) Receiver.mContext.getSystemService(Context.AUDIO_SERVICE);
		if (RtpStreamReceiver.speakermode == AudioManager.MODE_NORMAL)
			if (down ^ mAudioManager.getStreamVolume(stream()) == 0)
				mAudioManager.setStreamMute(stream(), down);
		if (down && down_time == 0) down_time = SystemClock.elapsedRealtime();
		if (!down ^ RtpStreamReceiver.speakermode != AudioManager.MODE_NORMAL)
			if (SystemClock.elapsedRealtime() - down_time < 500) {
				if (!down) down_time = 0;
				if (ogain > 1)
					if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
						if (gain != ogain) {
							gain = ogain;
							return;
						}
						if (mAudioManager.getStreamVolume(stream()) == mAudioManager.getStreamMaxVolume(stream())) return;
						gain = ogain / 2;
					} else {
						if (gain == ogain) {
							gain = ogain / 2;
							return;
						}
						if (mAudioManager.getStreamVolume(stream()) == 0) return;
						gain = ogain;
					}
				mAudioManager.adjustStreamVolume(stream(),
						keyCode == KeyEvent.KEYCODE_VOLUME_UP ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
						show ? AudioManager.FLAG_SHOW_UI : 0);
			}
		if (!down) down_time = 0;
	}

	static void setStreamVolume(final int stream, final int vol, final int flags) {
		new Thread() {
			public void run() {
				AudioManager am = (AudioManager) Receiver.mContext.getSystemService(Context.AUDIO_SERVICE);
				am.setStreamVolume(stream, vol, flags);
				if (stream == stream()) restored = true;
			}
		}.start();
	}

	static float gain, ogain;

	void restoreVolume() {
		switch (getMode()) {
			case AudioManager.MODE_IN_CALL:
				int oldring = PreferenceManager.getDefaultSharedPreferences(Receiver.mContext).getInt("oldring", 0);
				if (oldring > 0 && Integer.parseInt(Build.VERSION.SDK) < 25)
					setStreamVolume(AudioManager.STREAM_RING, (int) (am.getStreamMaxVolume(AudioManager.STREAM_RING) *
							org.sipdroid.sipua.ui.Settings.getEarGain() * 3), 0);
				track.setStereoVolume(AudioTrack.getMaxVolume() * (ogain = org.sipdroid.sipua.ui.Settings.getEarGain() * 2),
						AudioTrack.getMaxVolume() * org.sipdroid.sipua.ui.Settings.getEarGain() * 2);
				if (gain == 0 || ogain <= 1) gain = ogain;
				break;
			case AudioManager.MODE_NORMAL:
				track.setStereoVolume(AudioTrack.getMaxVolume(), AudioTrack.getMaxVolume());
				break;
		}
		setStreamVolume(stream(),
				PreferenceManager.getDefaultSharedPreferences(Receiver.mContext).getInt("volume" + speakermode,
						am.getStreamMaxVolume(stream()) * (speakermode == AudioManager.MODE_NORMAL ? 4 : 3) / 4), 0);
	}

	void saveVolume() {
		if (restored) {
			Editor edit = PreferenceManager.getDefaultSharedPreferences(Receiver.mContext).edit();
			edit.putInt("volume" + speakermode, am.getStreamVolume(stream()));
			edit.commit();
		}
	}

	void saveSettings() {
		if (!PreferenceManager.getDefaultSharedPreferences(Receiver.mContext).getBoolean(org.sipdroid.sipua.ui.Settings.PREF_OLDVALID, org.sipdroid.sipua.ui.Settings.DEFAULT_OLDVALID)) {
			int oldvibrate = am.getVibrateSetting(AudioManager.VIBRATE_TYPE_RINGER);
			int oldvibrate2 = am.getVibrateSetting(AudioManager.VIBRATE_TYPE_NOTIFICATION);
			if (!PreferenceManager.getDefaultSharedPreferences(Receiver.mContext).contains(org.sipdroid.sipua.ui.Settings.PREF_OLDVIBRATE2))
				oldvibrate2 = AudioManager.VIBRATE_SETTING_ON;
			Editor edit = PreferenceManager.getDefaultSharedPreferences(Receiver.mContext).edit();
			edit.putInt(org.sipdroid.sipua.ui.Settings.PREF_OLDVIBRATE, oldvibrate);
			edit.putInt(org.sipdroid.sipua.ui.Settings.PREF_OLDVIBRATE2, oldvibrate2);
			edit.putInt(org.sipdroid.sipua.ui.Settings.PREF_OLDRING, am.getStreamVolume(AudioManager.STREAM_RING));
			edit.putBoolean(org.sipdroid.sipua.ui.Settings.PREF_OLDVALID, true);
			edit.commit();
		}
	}

	public static int getMode() {
		AudioManager am = (AudioManager) Receiver.mContext.getSystemService(Context.AUDIO_SERVICE);
		if (Integer.parseInt(Build.VERSION.SDK) >= 5)
			return am.isSpeakerphoneOn() ? AudioManager.MODE_NORMAL : AudioManager.MODE_IN_CALL;
		else
			return am.getMode();
	}

	static boolean samsung;

	@TargetApi(23)
	public static void setMode(int mode) {
		Editor edit = PreferenceManager.getDefaultSharedPreferences(Receiver.mContext).edit();
		edit.putBoolean(org.sipdroid.sipua.ui.Settings.PREF_SETMODE, true);
		edit.commit();
		AudioManager am = (AudioManager) Receiver.mContext.getSystemService(Context.AUDIO_SERVICE);
		if (Integer.parseInt(Build.VERSION.SDK) >= 5) {
			am.setSpeakerphoneOn(mode == AudioManager.MODE_NORMAL);
			if (samsung) RtpStreamSender.changed = true;
			if (Integer.parseInt(Build.VERSION.SDK) >= 31) {
				ArrayList<Integer> targetTypes = new ArrayList<>();
				if (mode != AudioManager.MODE_NORMAL) {
					targetTypes.add(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE);
				} else {
					targetTypes.add(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
				}
				List<AudioDeviceInfo> devices = null;
				if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
					devices = am.getAvailableCommunicationDevices();
				}
				outer:
				for (Integer targetType : targetTypes) {
					for (AudioDeviceInfo device : devices) {
						if (device.getType() == targetType) {
							boolean result = false;
							if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
								result = am.setCommunicationDevice(device);
							}
							if (result) break outer;
						}
					}
				}
			}
		} else {
			am.setMode(mode);
		}
	}

	public static void restoreMode() {
		if (PreferenceManager.getDefaultSharedPreferences(Receiver.mContext).getBoolean(org.sipdroid.sipua.ui.Settings.PREF_SETMODE, org.sipdroid.sipua.ui.Settings.DEFAULT_SETMODE)) {
			Editor edit = PreferenceManager.getDefaultSharedPreferences(Receiver.mContext).edit();
			edit.putBoolean(org.sipdroid.sipua.ui.Settings.PREF_SETMODE, false);
			edit.commit();
			if (Receiver.pstn_state == null || Receiver.pstn_state.equals("IDLE")) {
				AudioManager am = (AudioManager) Receiver.mContext.getSystemService(Context.AUDIO_SERVICE);
				if (Integer.parseInt(Build.VERSION.SDK) >= 5)
					am.setSpeakerphoneOn(false);
				else
					am.setMode(AudioManager.MODE_NORMAL);
			}
		}
	}

	void initMode() {
		samsung = Build.MODEL.contains("SAMSUNG") || Build.MODEL.contains("SPH-") ||
				Build.MODEL.contains("SGH-") || Build.MODEL.contains("GT-");
		if (Receiver.call_state == UserAgent.UA_STATE_INCOMING_CALL &&
				(Receiver.pstn_state == null || Receiver.pstn_state.equals("IDLE")))
			setMode(AudioManager.MODE_NORMAL);
	}

	public static void restoreSettings() {
		if (PreferenceManager.getDefaultSharedPreferences(Receiver.mContext).getBoolean(org.sipdroid.sipua.ui.Settings.PREF_OLDVALID, org.sipdroid.sipua.ui.Settings.DEFAULT_OLDVALID)) {
			AudioManager am = (AudioManager) Receiver.mContext.getSystemService(Context.AUDIO_SERVICE);
			ContentResolver cr = Receiver.mContext.getContentResolver();
			int oldvibrate = PreferenceManager.getDefaultSharedPreferences(Receiver.mContext).getInt(org.sipdroid.sipua.ui.Settings.PREF_OLDVIBRATE, org.sipdroid.sipua.ui.Settings.DEFAULT_OLDVIBRATE);
			int oldvibrate2 = PreferenceManager.getDefaultSharedPreferences(Receiver.mContext).getInt(org.sipdroid.sipua.ui.Settings.PREF_OLDVIBRATE2, org.sipdroid.sipua.ui.Settings.DEFAULT_OLDVIBRATE2);
			am.setVibrateSetting(AudioManager.VIBRATE_TYPE_RINGER, oldvibrate);
			am.setVibrateSetting(AudioManager.VIBRATE_TYPE_NOTIFICATION, oldvibrate2);
			int oldring = PreferenceManager.getDefaultSharedPreferences(Receiver.mContext).getInt("oldring", 0);
			if (oldring > 0 && Integer.parseInt(Build.VERSION.SDK) < 25)
				am.setStreamVolume(AudioManager.STREAM_RING, oldring, 0);
			Editor edit = PreferenceManager.getDefaultSharedPreferences(Receiver.mContext).edit();
			edit.putBoolean(org.sipdroid.sipua.ui.Settings.PREF_OLDVALID, false);
			edit.commit();
			PowerManager pm = (PowerManager) Receiver.mContext.getSystemService(Context.POWER_SERVICE);
			@SuppressLint("InvalidWakeLockTag") PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "Sipdroid.RtpStreamReceiver");
			wl.acquire(1000);
		}
		restoreMode();
	}

	public static float good, late, lost, loss, loss2;

	void empty() {
		try {
			synchronized (socketLock) {
				if (rtp_socket != null) {
					rtp_socket.getDatagramSocket().setSoTimeout(1);
					while (running) {
						rtp_socket.receive(rtp_packet);
						Log.i("RtpStreamReceiver", "Just receive encrypted packet "+rtp_packet.getPayload());
						RawPacket zrtp_encrypted = new RawPacket(rtp_packet.getPacket(), 0, rtp_packet.getLength());
						zrtpEngine.reverseTransform(zrtp_encrypted);
						Log.i("RtpStreamReceiver", "After packet decrypt "+rtp_packet.getPayload());
						rtp_packet = new RtpPacket(zrtp_encrypted.getBuffer(), zrtp_encrypted.getLength());
					}
				}
			}
		} catch (SocketException e2) {
			if (!Sipdroid.release) Log.e(TAG, "SocketException in empty", e2);
		} catch (IOException e) {
			if (DEBUG) Log.w(TAG, "IOException in empty, ignoring", e);
		}
		try {
			synchronized (socketLock) {
				if (rtp_socket != null) {
					rtp_socket.getDatagramSocket().setSoTimeout(SO_TIMEOUT);
				}
			}
		} catch (SocketException e2) {
			if (!Sipdroid.release) Log.e(TAG, "SocketException setting timeout", e2);
		}
		seq = 0;
	}

	RtpPacket rtp_packet = new RtpPacket(new byte[BUFFER_SIZE + 12], 0);

	void setCodec() {
		synchronized (this) {
			AudioTrack oldtrack = track;
			p_type.codec.init();
			codec = p_type.codec.getTitle();
			mu = p_type.codec.samp_rate() / 8000;
			maxjitter = AudioTrack.getMinBufferSize(p_type.codec.samp_rate(),
					AudioFormat.CHANNEL_CONFIGURATION_MONO, AudioFormat.ENCODING_PCM_16BIT);
			if (maxjitter < 2 * 1024 * mu) maxjitter = 2 * 1024 * mu;
			oldtrack = track;
			track = new AudioTrack(stream(), p_type.codec.samp_rate(), AudioFormat.CHANNEL_CONFIGURATION_MONO,
					AudioFormat.ENCODING_PCM_16BIT, maxjitter * 2, AudioTrack.MODE_STREAM);
			maxjitter /= 2 * 2;
			minjitter = minjitteradjust = 500 * mu;
			jitter = 875 * mu;
			if (jitter > maxjitter) jitter = maxjitter;
			devheadroom = Math.pow(jitter / 5, 2);
			timeout = 1;
			timeoutstart = System.currentTimeMillis();
			luser = luser2 = -8000 * mu;
			cnt = cnt2 = user = lserver = 0;
			if (oldtrack != null) {
				oldtrack.stop();
				oldtrack.release();
			}
			track.play();
		}
	}

	void write(short a[], int b, int c) {
		synchronized (this) {
			user += track.write(a, b, c);
		}
	}

	@SuppressLint("InvalidWakeLockTag")
	void lock(boolean lock) {
		try {
			if (lock) {
				boolean lockNew = keepon ||
						Receiver.call_state == UserAgent.UA_STATE_HOLD ||
						Receiver.call_state == UserAgent.UA_STATE_INCOMING_CALL ||
						RtpStreamReceiver.speakermode == AudioManager.MODE_NORMAL ||
						Receiver.headset > 0 || Receiver.docked > 0;
				if (lockFirst || lockLast != lockNew) {
					lockLast = lockNew;
					lock(false);
					lockFirst = false;
					if (pwl == null) {
						PowerManager pm = (PowerManager) Receiver.mContext.getSystemService(Context.POWER_SERVICE);
						pwl = pm.newWakeLock(lockNew ? (PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP) : PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "Sipdroid.Receiver");
						pwl.acquire();
					}
				}
			} else {
				lockFirst = true;
				if (pwl != null) {
					pwl.release();
					pwl = null;
				}
			}
		} catch (Exception e) {
			Log.e(TAG, "Exception in lock", e);
		}
		if (lock) {
			if (pwl2 == null) {
				PowerManager pm = (PowerManager) Receiver.mContext.getSystemService(Context.POWER_SERVICE);
				WifiManager wm = (WifiManager) Receiver.mContext.getSystemService(Context.WIFI_SERVICE);
				pwl2 = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sipdroid.Receiver");
				pwl2.acquire();
				wwl = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Sipdroid.Receiver");
				wwl.acquire();
			}
		} else if (pwl2 != null) {
			pwl2.release();
			pwl2 = null;
			wwl.release();
		}
	}

	void newjitter(boolean inc) {
		if (good == 0 || lost / good > 0.01 || call_recorder != null) return;
		int newjitter = (int) Math.sqrt(devheadroom) * 7 + (inc ? minjitteradjust : 0);
		if (newjitter < minjitter) newjitter = minjitter;
		if (newjitter > maxjitter) newjitter = maxjitter;
		if (!inc && (Math.abs(jitter - newjitter) < minjitteradjust || newjitter >= jitter)) return;
		if (inc && newjitter <= jitter) return;
		jitter = newjitter;
		late = 0;
		avgcnt = 0;
		luser2 = user;
	}

	@Override
	public void run() {
		boolean nodata = PreferenceManager.getDefaultSharedPreferences(Receiver.mContext)
				.getBoolean(Settings.PREF_NODATA, Settings.DEFAULT_NODATA);
		keepon = PreferenceManager.getDefaultSharedPreferences(Receiver.mContext)
				.getBoolean(Settings.PREF_KEEPON, Settings.DEFAULT_KEEPON);

		if (rtp_socket == null) {
			if (DEBUG) Log.e(TAG, "ERROR: RTP socket is null");
			return;
		}

		byte[] buffer = new byte[BUFFER_SIZE + 12];
		rtp_packet = new RtpPacket(buffer, 0);

		if (DEBUG) Log.d(TAG, "Reading blocks of max " + buffer.length + " bytes");

		running = true;
		enableBluetooth(PreferenceManager.getDefaultSharedPreferences(Receiver.mContext)
				.getBoolean(Settings.PREF_BLUETOOTH, Settings.DEFAULT_BLUETOOTH));
		restored = false;

		Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
		am = (AudioManager) Receiver.mContext.getSystemService(Context.AUDIO_SERVICE);
		cr = Receiver.mContext.getContentResolver();
		if (am == null) {
			Log.e(TAG, "Failed to get AudioManager, halting");
			halt();
			return;
		}

		try {
			tg = new ToneGenerator(AudioManager.STREAM_VOICE_CALL,
					(int) Math.min(ToneGenerator.MAX_VOLUME, ToneGenerator.MAX_VOLUME * 2 * Settings.getEarGain()));
		} catch (RuntimeException e) {
			Log.e(TAG, "Failed to initialize ToneGenerator", e);
			tg = null;
		}

		saveSettings();
		am.setVibrateSetting(AudioManager.VIBRATE_TYPE_RINGER, AudioManager.VIBRATE_SETTING_OFF);
		am.setVibrateSetting(AudioManager.VIBRATE_TYPE_NOTIFICATION, AudioManager.VIBRATE_SETTING_OFF);
		if (oldvol == -1) oldvol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
		initMode();
		setCodec();
		short lin[] = new short[BUFFER_SIZE];
		short lin2[] = new short[BUFFER_SIZE];
		int server, headroom, todo, len = 0, m = 1, expseq, getseq, vm = 1, gap, gseq;

		System.gc();
		empty();
		lockFirst = true;

		while (running) {
			synchronized (stateLock) {
				lock(Receiver.call_state != UserAgent.UA_STATE_INCOMING_CALL);
				if (Receiver.call_state == UserAgent.UA_STATE_HOLD) {
					lock(false);
					if (tg != null) tg.stopTone();
					track.pause();
					while (running && Receiver.call_state == UserAgent.UA_STATE_HOLD) {
						try {
							Thread.sleep(1000);
						} catch (InterruptedException e) {
							Log.w(TAG, "Interrupted while on hold", e);
						}
					}
					track.play();
					System.gc();
					timeout = 1;
					timeoutstart = System.currentTimeMillis();
					luser = luser2 = -8000 * mu;
				}
			}

			synchronized (socketLock) {
				if (rtp_socket == null || rtp_socket.isClosed()) {
					Log.w(TAG, "Socket is null or closed, attempting reconnect");
					if (RtpStreamSender.new_socket != null) {
						rtp_socket = new RtpSocket(RtpStreamSender.new_socket);
						RtpStreamSender.new_socket = null;
						if (DEBUG) Log.d(TAG, "Reconnected to new socket");
					} else {
						break;
					}
				}

				try {
					rtp_socket.receive(rtp_packet);
					Log.i("RtpStreamReceiver", "Just receive encrypted packet "+rtp_packet.getPayload());
					RawPacket zrtp_encrypted = new RawPacket(rtp_packet.getPacket(), 0, rtp_packet.getLength());
					zrtpEngine.reverseTransform(zrtp_encrypted);
					Log.i("RtpStreamReceiver", "After packet decrypt "+rtp_packet.getPayload());
					rtp_packet = new RtpPacket(zrtp_encrypted.getBuffer(), zrtp_encrypted.getLength());
					if (timeout != 0) {
						if (tg != null) tg.stopTone();
						empty();
					}
					timeout = 0;
					timeoutstart = System.currentTimeMillis();
					if (running && timeout == 0) {
						gseq = rtp_packet.getSequenceNumber();
						if (seq == gseq) {
							m++;
							continue;
						}
						gap = (gseq - seq) & 0xff;
						if (gap > 240) continue;
						server = track.getPlaybackHeadPosition();
						headroom = user - server;

						if (headroom > 2 * jitter) cnt += len;
						else cnt = 0;

						if (lserver == server) cnt2++;
						else cnt2 = 0;

						if (cnt <= 500 * mu || cnt2 >= 2 || headroom - jitter < len ||
								p_type.codec.number() != 8 || p_type.codec.number() != 0) {
							if (rtp_packet.getPayloadType() != p_type.number && p_type.change(rtp_packet.getPayloadType())) {
								saveVolume();
								setCodec();
								restoreVolume();
								codec = p_type.codec.getTitle();
							}
							len = p_type.codec.decode(buffer, lin, rtp_packet.getPayloadLength());

							if (call_recorder != null) call_recorder.writeIncoming(lin, 0, len);

							if (speakermode == AudioManager.MODE_NORMAL) calc(lin, 0, len);
							else if (gain > 1) calc2(lin, 0, len);
						}

						if (cnt == 0) avgheadroom = avgheadroom * 0.99 + (double) headroom * 0.01;
						if (avgcnt++ > 300) devheadroom = devheadroom * 0.999 + Math.pow(Math.abs(headroom - avgheadroom), 2) * 0.001;

						if (headroom < 250 * mu) {
							late++;
							avgcnt += 10;
							if (avgcnt > 400) newjitter(true);
							todo = jitter - headroom;
							write(lin2, 0, todo > BUFFER_SIZE ? BUFFER_SIZE : todo);
						}

						if (cnt > 500 * mu && cnt2 < 2) {
							todo = headroom - jitter;
							if (todo < len) write(lin, todo, len - todo);
						} else write(lin, 0, len);

						if (seq != 0) {
							getseq = gseq & 0xff;
							expseq = ++seq & 0xff;
							if (m == RtpStreamSender.m) vm = m;
							gap = (getseq - expseq) & 0xff;
							if (gap > 0) {
								if (gap > 100) gap = 1;
								loss += gap;
								lost += gap;
								good += gap - 1;
								loss2++;
							} else {
								if (m < vm) {
									loss++;
									loss2++;
								}
							}
							good++;
							if (good > 110) {
								good *= 0.99;
								lost *= 0.99;
								loss *= 0.99;
								loss2 *= 0.99;
								late *= 0.99;
							}
						}
						m = 1;
						seq = gseq;

						if (user >= luser + 8000 * mu && (
								Receiver.call_state == UserAgent.UA_STATE_INCALL ||
										Receiver.call_state == UserAgent.UA_STATE_OUTGOING_CALL)) {
							if (luser == -8000 * mu || getMode() != speakermode) {
								saveVolume();
								setMode(speakermode);
								restoreVolume();
							}
							luser = user;
							if (user >= luser2 + 160000 * mu) newjitter(false);
						}
						lserver = server;
					}
				} catch (IOException e) {
					Log.e(TAG, "IO error during receive", e);
					if (e instanceof SocketException && "Socket closed".equals(e.getMessage())) {
						Log.w(TAG, "Socket closed detected, attempting reconnect");
						if (RtpStreamSender.new_socket != null) {
							rtp_socket = new RtpSocket(RtpStreamSender.new_socket);
							RtpStreamSender.new_socket = null;
							if (DEBUG) Log.d(TAG, "Reconnected to new socket");
						} else {
							break;
						}
					} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
						if (e.getCause() instanceof ErrnoException &&
								"EBADF".equals(((ErrnoException) e.getCause()).getMessage())) {
							Log.w(TAG, "Bad file descriptor detected, stopping receiver");
							break;
						} else if (timeout == 0 && nodata) {
							if (tg != null) tg.startTone(ToneGenerator.TONE_SUP_RINGTONE);
							rtp_socket.getDatagramSocket().disconnect();
							if (RtpStreamSender.new_socket != null) {
								rtp_socket = new RtpSocket(RtpStreamSender.new_socket);
								RtpStreamSender.new_socket = null;
							}
							timeout = (System.currentTimeMillis() - timeoutstart) / 1000 + 1;
							if (timeout > 60) {
								Receiver.engine(Receiver.mContext).rejectcall();
								break;
							}
						}
					}
				}
			}
		}
		cleanup();
	}

	private void cleanup() {
		synchronized (socketLock) {
			if (rtp_socket != null) {
				if (!rtp_socket.isClosed()) {
					rtp_socket.close();
					if (DEBUG) Log.d(TAG, "RTP socket closed in cleanup");
				}
				rtp_socket = null;
			}
		}
		synchronized (stateLock) {
			lock(false);
			if (track != null) {
				track.stop();
				track.release();
				track = null;
			}
			if (tg != null) {
				tg.stopTone();
				tg.release();
				if (DEBUG) Log.d(TAG, "ToneGenerator released");
			}
			saveVolume();
			if (am != null) {
				am.setStreamVolume(AudioManager.STREAM_MUSIC, oldvol, 0);
				am.setVibrateSetting(AudioManager.VIBRATE_TYPE_RINGER, AudioManager.VIBRATE_SETTING_ON);
				am.setVibrateSetting(AudioManager.VIBRATE_TYPE_NOTIFICATION, AudioManager.VIBRATE_SETTING_ON);
			}
			restoreSettings();
			enableBluetooth(false);
			oldvol = -1;
			if (p_type != null && p_type.codec != null) p_type.codec.close();
			if (call_recorder != null) {
				call_recorder.stopIncoming();
				call_recorder = null;
			}
			running = false;
			if (DEBUG) Log.d(TAG, "RTP receiver terminated");
		}
		cleanupBluetooth();
	}

	private static void println(String str) {
		if (!Sipdroid.release) Log.d(TAG, "RtpStreamReceiver: " + str);
	}

	public static int byte2int(byte b) {
		return (b + 0x100) % 0x100;
	}

	public static int byte2int(byte b1, byte b2) {
		return (((b1 + 0x100) % 0x100) << 8) + (b2 + 0x100) % 0x100;
	}

	public static String getCodec() {
		return codec;
	}
}