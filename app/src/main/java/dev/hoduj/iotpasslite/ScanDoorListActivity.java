package dev.hoduj.iotpasslite;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** "출입문 스캔" — the port of the original app's MainScanDoorList screen. Instead of typing a
 *  reader's MAC address by hand, stand near the door and tap it off a live list. This is a
 *  foreground-only scan (runs while this screen is visible, stops the moment it isn't) using no
 *  device-address filter — just the IoTPass reader service UUID, which any BLE scanner app could
 *  see. It doesn't grant access to anything; it only helps you find a MAC address that's already
 *  physically broadcasting in front of you. */
public class ScanDoorListActivity extends Activity {

    static final String EXTRA_SELECTED_MAC = "selected_mac";

    private static final int PERMISSION_REQUEST_CODE = 2001;

    private final Map<String, Integer> foundByAddress = new LinkedHashMap<>();
    private ArrayAdapter<String> adapter;
    private List<String> orderedAddresses = new ArrayList<>();
    private BluetoothLeScanner scanner;
    private boolean scanning;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean refreshPending;

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            String address = result.getDevice().getAddress();
            foundByAddress.put(address, result.getRssi());
            if (!refreshPending) {
                refreshPending = true;
                handler.postDelayed(() -> {
                    refreshPending = false;
                    refreshList();
                }, 500L);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            runOnUiThread(() -> toast("스캔 실패 (code " + errorCode + ")"));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan_door_list);

        ListView listView = findViewById(R.id.scan_result_list);
        adapter = new ArrayAdapter<>(this, R.layout.item_door, new ArrayList<>());
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(this::onResultTapped);

        findViewById(R.id.scan_root).setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        findViewById(R.id.scan_cancel_button).setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        startScanning();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopScanning();
        handler.removeCallbacksAndMessages(null);
        refreshPending = false;
    }

    private void onResultTapped(AdapterView<?> parent, View view, int position, long id) {
        String address = orderedAddresses.get(position);
        Intent result = new Intent();
        result.putExtra(EXTRA_SELECTED_MAC, address);
        setResult(RESULT_OK, result);
        finish();
    }

    private void startScanning() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN}, PERMISSION_REQUEST_CODE);
            return;
        }
        scanner = leScanner();
        if (scanner == null) {
            toast(getString(R.string.error_bluetooth_off));
            finish();
            return;
        }
        try {
            scanner.startScan(DoorProtocol.discoveryScanFilters(), DoorProtocol.foregroundDiscoverySettings(), scanCallback);
            scanning = true;
        } catch (SecurityException e) {
            toast(getString(R.string.error_permission_denied));
            finish();
        }
    }

    private void stopScanning() {
        if (scanning && scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (SecurityException ignored) {
                // Nothing to clean up if the permission was already revoked underneath us.
            }
        }
        scanning = false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST_CODE) {
            return;
        }
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            startScanning();
        } else {
            toast(getString(R.string.error_permission_denied));
            finish();
        }
    }

    private BluetoothLeScanner leScanner() {
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        if (manager == null || manager.getAdapter() == null || !manager.getAdapter().isEnabled()) {
            return null;
        }
        return manager.getAdapter().getBluetoothLeScanner();
    }

    private void refreshList() {
        // Strongest signal (closest reader) first — most useful when several doors are in range.
        orderedAddresses = new ArrayList<>(foundByAddress.keySet());
        orderedAddresses.sort((a, b) -> foundByAddress.get(b) - foundByAddress.get(a));

        List<String> rows = new ArrayList<>(orderedAddresses.size());
        for (String address : orderedAddresses) {
            rows.add(address + "  (" + foundByAddress.get(address) + " dBm)");
        }
        adapter.clear();
        adapter.addAll(rows);

        TextView empty = findViewById(R.id.scan_empty_text);
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void toast(int resId) {
        toast(getString(resId));
    }
}
