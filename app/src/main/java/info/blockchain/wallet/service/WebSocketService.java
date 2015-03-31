package info.blockchain.wallet.service;

import java.util.Timer;
import java.util.TimerTask;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import info.blockchain.wallet.payload.PayloadFactory;

public class WebSocketService extends android.app.Service	{

	private static final long checkIfNotConnectedDelay = 15000L;

	private WebSocketHandler webSocketHandler = null;

	Timer timer = new Timer();

	private final Handler handler = new Handler();

	private NotificationManager nm;
	private static final int NOTIFICATION_ID_CONNECTED = 0;
	private static final int NOTIFICATION_ID_COINS_RECEIVED = 1;
	private static final int NOTIFICATION_ID_COINS_SENT = 3;
	public static boolean isRunning = false;

	public class LocalBinder extends Binder
	{
		public WebSocketService getService()
		{
			return WebSocketService.this;
		}
	}

	private final IBinder mBinder = new LocalBinder();

	@Override
	public IBinder onBind(final Intent intent)
	{
		return mBinder;
	}

	@Override
	public void onCreate()
	{
		super.onCreate();

		isRunning = true;
		
		nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

		final IntentFilter intentFilter = new IntentFilter();
		intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);

		int nbAccounts = 0;
		try {
			nbAccounts = PayloadFactory.getInstance().get().getHdWallet().getAccounts().size();
		}
		catch(java.lang.IndexOutOfBoundsException e) {
			return;
		}
		
		String[] xpubs = new String[nbAccounts];
		for(int i = 0; i < nbAccounts; i++) {
			String s = PayloadFactory.getInstance().get().getHdWallet().getAccounts().get(i).getXpub();
			if(s != null && s.length() > 0) {
				xpubs[i] = s;
			}
		}

		int nbLegacy = PayloadFactory.getInstance().get().getLegacyAddresses().size();
		String[] addrs = new String[nbLegacy];
		for(int i = 0; i < nbLegacy; i++) {
			String s = PayloadFactory.getInstance().get().getLegacyAddresses().get(i).getAddress();
			if(s != null && s.length() > 0) {
				addrs[i] = PayloadFactory.getInstance().get().getLegacyAddresses().get(i).getAddress();
			}
		}

		webSocketHandler = new WebSocketHandler(WebSocketService.this, PayloadFactory.getInstance().get().getGuid(), xpubs, addrs);

		connectToWebsocketIfNotConnected();

		timer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				handler.post(new Runnable() {
					@Override
					public void run() {
						connectToWebsocketIfNotConnected();
					}
				});
			}
		}, 5000, checkIfNotConnectedDelay);
	}

	public void connectToWebsocketIfNotConnected()
	{
		try {
			if(!webSocketHandler.isConnected()) {
				webSocketHandler.start();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void stop() {
		try {
			webSocketHandler.stop(); 
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void onDestroy()
	{
		isRunning = false;
		
		stop();

		handler.removeCallbacksAndMessages(null);

		handler.postDelayed(new Runnable()
		{
			public void run()
			{
				nm.cancel(NOTIFICATION_ID_CONNECTED);
			}
		}, 2000);

		super.onDestroy();
	}

	/*
	public void notifyWidgets()
	{
		final Context context = getApplicationContext();
		// notify widgets
		final AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
		for (final AppWidgetProviderInfo providerInfo : appWidgetManager.getInstalledProviders())
		{
			// limit to own widgets
			if (providerInfo.provider.getPackageName().equals(context.getPackageName()))
			{
				final Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
				intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetManager.getAppWidgetIds(providerInfo.provider));
				context.sendBroadcast(intent);
			}
		}
	}
	*/
}