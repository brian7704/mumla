package se.lublin.mumla.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.gson.Gson;

import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.location.AddCookiesInterceptor;
import se.lublin.mumla.location.Device;
import se.lublin.mumla.location.Position;
import se.lublin.mumla.location.ReceivedCookiesInterceptor;
import se.lublin.mumla.location.TraccarInterface;
import se.lublin.mumla.location.WebsocketMessage;
import se.lublin.mumla.util.HumlaServiceFragment;

public class Map extends HumlaServiceFragment implements MapListener {
    private final String TAG = "Map";
    private MapView mapView;
    private FloatingActionButton ptt;
    private FloatingActionButton followMe;
    private Settings settings;
    private TraccarInterface traccarInterface;
    private Retrofit retrofit;
    private boolean loggedIntoTraccar = false;
    private HashMap<Integer, Marker> markers = new HashMap<>();
    private HashMap<Integer, Marker> labels = new HashMap<>();
    private OkHttpClient client;
    private MyLocationNewOverlay myLocationNewOverlay;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreateView");
        Configuration.getInstance().setUserAgentValue(inflater.getContext().getPackageName());
        return inflater.inflate(R.layout.map, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Log.d(TAG, "onViewCreated");
        settings = Settings.getInstance(getContext());
        checkFineLocationPermission();
        checkBackgroundLocationPermission();
        mapView = (MapView) getView().findViewById(R.id.mapview);
        mapView.setTileSource(TileSourceFactory.DEFAULT_TILE_SOURCE);
        mapView.setMultiTouchControls(true);
        mapView.setTilesScaledToDpi(true);
        mapView.getController().setZoom(3.0);
        mapView.setExpectedCenter(new GeoPoint(0, 0));
        mapView.setFlingEnabled(true);

        ptt = getView().findViewById(R.id.ptt);
        ptt.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (getService() != null) {
                        getService().onTalkKeyDown();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (getService() != null) {
                        getService().onTalkKeyUp();
                    }
                    break;
            }
            return true;
        });

        followMe = getView().findViewById(R.id.follow_me);
        followMe.setOnClickListener(view1 -> {
            myLocationNewOverlay.enableFollowLocation();
            mapView.zoomToBoundingBox(getBoundingBox(), true);
        });

        myLocationNewOverlay = new MyLocationNewOverlay(mapView);
        myLocationNewOverlay.enableMyLocation();
        myLocationNewOverlay.setDrawAccuracyEnabled(true);
        myLocationNewOverlay.runOnFirstFix(() -> {
            getActivity().runOnUiThread(() -> {
                mapView.zoomToBoundingBox(getBoundingBox(), true);
                myLocationNewOverlay.enableFollowLocation();
            });
        });
        mapView.getOverlays().add(myLocationNewOverlay);

        mapView.addMapListener(this);

        OkHttpClient.Builder builder = new OkHttpClient.Builder();

        builder.addInterceptor(new AddCookiesInterceptor(getContext()));
        builder.addInterceptor(new ReceivedCookiesInterceptor(getContext()));
        client = builder.build();

        try {
            retrofit = new Retrofit.Builder()
                    .baseUrl(settings.getTraccarUrl())
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();
            traccarInterface = retrofit.create(TraccarInterface.class);
            traccarLogin();
        } catch (IllegalArgumentException e) {
            Toast.makeText(getContext(), getString(R.string.invalid_traccar_url),
                    Toast.LENGTH_LONG).show();
        }
    }

    private BoundingBox getBoundingBox() {
        BoundingBox bbox = new BoundingBox();
        GeoPoint location = myLocationNewOverlay.getMyLocation();
        try {
            double north = location.getLatitude() + .2;
            double east = location.getLongitude() + .2;
            double south = location.getLatitude() - .2;
            double west = location.getLongitude() - .2;
            bbox.set(north, east, south, west);
        } catch (NullPointerException e) {
            bbox.set(180, 90, -180, 90);
        }
        return bbox;
    }

    @Override
    public void onResume() {
        super.onResume();
        myLocationNewOverlay.enableMyLocation();
        traccarLogin();
    }

    @Override
    public void onPause() {
        super.onPause();
        myLocationNewOverlay.disableMyLocation();
    }

    private void traccarLogin() {
        if (loggedIntoTraccar || !settings.isTraccarEnabled())
            return;

        try {
            traccarInterface.login(settings.getTraccarUsername(), settings.getTraccarPassword()).enqueue(new Callback<Void>() {
                @Override
                public void onResponse(@NonNull Call<Void> call, @NonNull Response<Void> response) {
                    if (response.code() == 200) {
                        loggedIntoTraccar = true;
                        setupWebsocket();
                        Log.d(TAG, "Getting devices");
                        traccarInterface.devices().enqueue(new Callback<ArrayList<Device>>() {
                            @Override
                            public void onResponse(@NonNull Call<ArrayList<Device>> call, @NonNull Response<ArrayList<Device>> response) {
                                if (response.code() == 200) {
                                    for (Device device : Objects.requireNonNull(response.body())) {
                                        handleDevice(device);
                                    }
                                } else {
                                    Log.d(TAG, "Devices status code " + response.code());
                                }
                            }

                            @Override
                            public void onFailure(@NonNull Call<ArrayList<Device>> call, @NonNull Throwable t) {

                            }
                        });
                    } else {
                        Toast.makeText(getContext(), "Traccar login failed: " + response.message(),
                                Toast.LENGTH_LONG).show();
                        Log.d(TAG, "Traccar login failed: " + response.message());
                    }
                }

                @Override
                public void onFailure(@NonNull Call<Void> call, @NonNull Throwable t) {
                    Log.d(TAG, "Traccar login failed: " + t.getLocalizedMessage());
                    Toast.makeText(getContext(), "Traccar login failed: " + t.getLocalizedMessage(),
                            Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception e) {
            Log.d(TAG, "Failed to log into traccar: " + e.getLocalizedMessage());
            Toast.makeText(getContext(), "Traccar login failed: " + e.getLocalizedMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void handleDevice(Device device) {
        if (device.getUniqueId().equals(settings.getTraccarId()))
            return;

        if (!markers.containsKey(device.getId())) {
            Marker marker = new Marker(mapView);
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            marker.setTitle(device.getName());
            Drawable arrow;

            if (Objects.equals(device.getStatus(), "online")) {
                arrow = ResourcesCompat.getDrawable(getResources(), R.drawable.arrow_green, null);
            } else {
                arrow = ResourcesCompat.getDrawable(getResources(), R.drawable.arrow_red, null);
            }

            marker.setIcon(arrow);
            mapView.getOverlays().add(marker);
            markers.put(device.getId(), marker);

            Marker label = new Marker(mapView);
            label.setTextIcon(device.getName());
            mapView.getOverlays().add(label);
            labels.put(device.getId(), label);

            traccarInterface.positions(device.getPositionId()).enqueue(new Callback<ArrayList<Position>>() {
                @Override
                public void onResponse(@NonNull Call<ArrayList<Position>> call, @NonNull Response<ArrayList<Position>> response) {
                    if (response.code() == 200) {
                        handlePoint(Objects.requireNonNull(response.body()).get(0));
                    } else {
                        Log.d(TAG, "Failed to get position: " + response.message());
                    }
                }

                @Override
                public void onFailure(@NonNull Call<ArrayList<Position>> call, @NonNull Throwable t) {
                    Log.d(TAG, "Failed to get position: " + t.getLocalizedMessage());
                }
            });
        }
    }

    private void handlePoint(Position position) {
        if (markers.containsKey(position.getDeviceId())) {
            markers.get(position.getDeviceId()).setPosition(new GeoPoint(position.getLatitude(), position.getLongitude()));
            markers.get(position.getDeviceId()).setRotation(position.getCourse());
            labels.get(position.getDeviceId()).setPosition(new GeoPoint(position.getLatitude(), position.getLongitude()));
        }
    }

    private void setupWebsocket() {
        Log.d(TAG, "setupWebsocket");
        Request.Builder requestBuilder = new Request.Builder();
        String url = settings.getTraccarUrl().replace("http", "ws");
        if (url.endsWith("/"))
            url += "api/socket";
        else
            url += "/api/socket";
        requestBuilder.url(url);
        client.newWebSocket(requestBuilder.build(), webSocketListener);
        Log.d(TAG, "end websocket");
    }

    private final WebSocketListener webSocketListener = new WebSocketListener() {
        @Override
        public void onOpen(WebSocket webSocket, okhttp3.Response response) {
            super.onOpen(webSocket, response);
            Log.d(TAG, "onOpen " + response.message());
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            super.onMessage(webSocket, text);
            Gson gson = new Gson();
            WebsocketMessage message = gson.fromJson(text, WebsocketMessage.class);
            if (message.getPositions() != null) {
                for (Position position : message.getPositions()) {
                    handlePoint(position);
                }
            }

            if (message.getDevices() != null) {
                for (Device device : message.getDevices()) {
                    handleDevice(device);
                }
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            super.onMessage(webSocket, bytes);
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            super.onClosing(webSocket, code, reason);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            super.onClosed(webSocket, code, reason);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, okhttp3.Response response) {
            super.onFailure(webSocket, t, response);
            Log.d(TAG, "onFailure: " + t.getLocalizedMessage());
            Log.d(TAG, webSocket.request().toString());
        }
    };

    @Override
    public boolean onScroll(ScrollEvent event) {
        myLocationNewOverlay.disableFollowLocation();
        return true;
    }

    @Override
    public boolean onZoom(ZoomEvent event) {
        return false;
    }

    private void checkFineLocationPermission() {
        if (settings.isTraccarEnabled()) {
            if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                ArrayList<String> permissions = new ArrayList<>();
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
                ActivityCompat.requestPermissions(getActivity(), permissions.toArray(new String[0]),
                        MumlaActivity.PERMISSIONS_REQUEST_FINE_LOCATION);
                return;
            }
        }
    }

    private void checkBackgroundLocationPermission() {
        if (settings.isTraccarEnabled()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            != PackageManager.PERMISSION_GRANTED) {
                ArrayList<String> permissions = new ArrayList<>();
                permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
                ActivityCompat.requestPermissions(getActivity(), permissions.toArray(new String[0]),
                        MumlaActivity.PERMISSIONS_REQUEST_BACKGROUND_LOCATION);
                return;
            }
        }
    }
}
