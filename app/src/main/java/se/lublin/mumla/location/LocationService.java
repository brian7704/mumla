package se.lublin.mumla.location;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.app.MumlaActivity;

public class LocationService extends Service {

    private final String TAG = "LocationService";
    private LocationListener _locListener;
    private LocationManager _locManager;
    private NotificationManager notificationManager;
    private final String channelId = "LocationService";
    private final int notifyId = 12345;

    private boolean batteryCharging = false;
    private float batteryPercent = -1;
    private Settings settings;

    private TraccarInterface traccarInterface;
    private Retrofit retrofit;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL;

            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            batteryPercent = level * 100 / (float)scale;
        }
    };


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        Notification notification = showNotification(getString(R.string.sending_to_traccar));

        int type = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        }

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            startForeground(notifyId, notification, type);
        } else {
            startForeground(notifyId, notification);
        }
        return START_STICKY;
    }

    public Notification showNotification(String content) {
        // Bring MainActivity to the screen when the notification is pressed
        Intent intent = new Intent(getApplicationContext(), MumlaActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent launchActivity = PendingIntent.getActivity(getApplicationContext(), 1, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, channelId)
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(launchActivity)
                .setContentText(content);

        Notification notification = notificationBuilder.build();
        notificationManager.notify(notifyId, notification);

        return notification;
    }

    @Override
    public void onCreate() {
        settings = Settings.getInstance(getApplicationContext());
        retrofit = new Retrofit.Builder()
                .baseUrl(settings.getTraccarUrl())
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        traccarInterface = retrofit.create(TraccarInterface.class);

        _locListener = new MyLocationListener();
        _locManager = (LocationManager) getApplicationContext().getSystemService(Context.LOCATION_SERVICE);

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, TAG, NotificationManager.IMPORTANCE_HIGH);
            notificationManager.createNotificationChannel(channel);
        }

        this.registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));


        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        _locManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 0, _locListener);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopForeground(true);
        _locManager.removeUpdates(_locListener);
        unregisterReceiver(batteryReceiver);
        Log.d(TAG, "onDestroy");
    }

    class MyLocationListener implements LocationListener {
        @Override
        public void onLocationChanged(@NonNull Location location)
        {
            try {
                traccarInterface.osmAnd(settings.getTraccarId(), System.currentTimeMillis(), location.getLatitude(), location.getLongitude(),
                        location.getSpeed(), location.getAltitude(), location.getBearing(), location.getAccuracy(), batteryCharging, batteryPercent).execute();
            } catch (IOException e) {
                Log.e(TAG, "Failed to send location to traccar: " + e.getLocalizedMessage());
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
