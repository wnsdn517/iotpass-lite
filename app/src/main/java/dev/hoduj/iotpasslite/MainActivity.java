package dev.hoduj.iotpasslite;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.util.List;

/** Single-screen setup: which door reader to watch for, which phone number to present as the
 *  access credential, and an on/off switch for the background watcher. */
public class MainActivity extends Activity {

    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int SCAN_NEARBY_REQUEST_CODE = 1002;
    // Core function — gates the enable switch. POST_NOTIFICATIONS is deliberately NOT in this
    // list: it only affects whether the "Bluetooth is off" warning can be shown, and someone
    // denying it should never block the actual door-opening feature.
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE
    };
    private static final int OPTIONAL_PERMISSION_REQUEST_CODE = 1003;
    private static final int BT_ROW_REQUEST_CODE = 1004;

    private EditText doorMacInput;
    private EditText phoneNumberInput;
    private Switch enabledSwitch;
    private TextView statusText;
    private Button scanNearbyButton;
    private TextView permBt;
    private TextView permNoti;
    private TextView permBattery;
    private TextView permOverlay;
    private Button viewLogButton;

    /** True while we're programmatically setting the switch's checked state from saved prefs,
     *  so that doesn't get treated as a user action by the listener below. */
    private boolean suppressToggleCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        doorMacInput = findViewById(R.id.door_mac_input);
        phoneNumberInput = findViewById(R.id.phone_number_input);
        enabledSwitch = findViewById(R.id.enabled_switch);
        statusText = findViewById(R.id.status_text);
        scanNearbyButton = findViewById(R.id.scan_nearby_button);
        permBt = findViewById(R.id.perm_bt);
        permNoti = findViewById(R.id.perm_noti);
        permBattery = findViewById(R.id.perm_battery);
        permOverlay = findViewById(R.id.perm_overlay);
        viewLogButton = findViewById(R.id.view_log_button);

        doorMacInput.setText(Prefs.getDoorMacsAsText(this));
        phoneNumberInput.setText(Prefs.getPhoneNumber(this));

        setSwitchChecked(Prefs.isEnabled(this));
        updateEditableState();
        updateStatusText();
        updatePermissionRows();

        enabledSwitch.setOnCheckedChangeListener(this::onToggle);
        scanNearbyButton.setOnClickListener(v ->
                startActivityForResult(new Intent(this, ScanDoorListActivity.class), SCAN_NEARBY_REQUEST_CODE));
        permBt.setOnClickListener(v -> {
            if (!hasAllPermissions()) {
                requestPermissions(REQUIRED_PERMISSIONS, BT_ROW_REQUEST_CODE);
            }
        });
        permNoti.setOnClickListener(v -> {
            if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        OPTIONAL_PERMISSION_REQUEST_CODE);
            }
        });
        permOverlay.setOnClickListener(v -> {
            if (!SendOverlay.canOverlay(this)) {
                requestOverlayPermission();
            }
        });
        // Dev-only manual trigger for the overlay animation; hidden outside debug builds.
        Button overlayTestButton = findViewById(R.id.overlay_test_button);
        if (BuildConfig.DEBUG) {
            overlayTestButton.setOnClickListener(v -> SendOverlay.show(this));
        } else {
            overlayTestButton.setVisibility(android.view.View.GONE);
        }
        permBattery.setOnClickListener(v -> {
            if (!isBatteryExempt()) {
                requestIgnoreBatteryOptimizations();
            }
        });
        viewLogButton.setOnClickListener(v -> startActivity(new Intent(this, EventLogActivity.class)));

        // Re-arm after first frame so startup isn't delayed. Re-registering an active scan is a
        // harmless no-op and repairs drift after force-stop / OEM battery managers. Not logged:
        // it is not a user-visible event.
        if (Prefs.isEnabled(this) && hasAllPermissions()) {
            statusText.post(() -> DoorScanner.start(this, Prefs.getDoorMacs(this)));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionRows();
    }

    private void requestOverlayPermission() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            toast(R.string.error_start_failed);
        }
    }

    private boolean isBatteryExempt() {
        PowerManager pm = getSystemService(PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void updatePermissionRows() {
        setRow(permBt, R.string.perm_bt, hasAllPermissions());
        setRow(permNoti, R.string.perm_noti, hasPermission(Manifest.permission.POST_NOTIFICATIONS));
        setRow(permBattery, R.string.perm_battery, isBatteryExempt());
        setRow(permOverlay, R.string.perm_overlay, SendOverlay.canOverlay(this));
    }

    private void setRow(TextView row, int nameRes, boolean granted) {
        String name = getString(nameRes);
        row.setText(getString(granted ? R.string.perm_ok : R.string.perm_todo, name));
        row.setTextColor(getColor(granted ? R.color.ok : R.color.primary));
        row.setClickable(!granted);
    }

    @SuppressWarnings("BatteryLife") // The whole point of this button is to ask for the exemption.
    private void requestIgnoreBatteryOptimizations() {
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (Exception e) {
            toast(R.string.error_start_failed);
        }
    }

    private void onToggle(CompoundButton button, boolean checked) {
        if (suppressToggleCallback) {
            return;
        }
        if (checked) {
            tryEnable();
        } else {
            DoorScanner.stop(this);
            EventLog.log(this, EventLog.Type.SCAN_OFF, null, null);
            Prefs.setEnabled(this, false);
            updateEditableState();
            updateStatusText();
        }
    }

    private void tryEnable() {
        List<String> doorMacs = Prefs.parseDoorMacsText(doorMacInput.getText().toString());
        String phoneNumber = phoneNumberInput.getText().toString().trim();
        if (doorMacs.isEmpty() || phoneNumber.isEmpty()) {
            toast(R.string.error_missing_fields);
            setSwitchChecked(false);
            return;
        }

        if (!isBluetoothOn()) {
            toast(R.string.error_bluetooth_off);
            setSwitchChecked(false);
            return;
        }

        if (!hasAllPermissions()) {
            requestPermissions(REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE);
            // Leave the switch on; onRequestPermissionsResult finishes the job (or reverts it).
            return;
        }

        finishEnabling(doorMacs, phoneNumber);
    }

    private void finishEnabling(List<String> doorMacs, String phoneNumber) {
        boolean started = DoorScanner.start(this, doorMacs);
        if (!started) {
            toast(R.string.error_start_failed);
            setSwitchChecked(false);
            return;
        }
        Prefs.save(this, doorMacs, phoneNumber, true);
        EventLog.log(this, EventLog.Type.SCAN_ON, null, String.join(", ", doorMacs));
        updateEditableState();
        updateStatusText();

        // Best-effort only: this just enables the "Bluetooth is off" warning notification.
        // Denying it doesn't (and shouldn't) affect the switch above being on already.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    OPTIONAL_PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        updatePermissionRows();
        if (requestCode == BT_ROW_REQUEST_CODE && !hasAllPermissions()) {
            // Denied (possibly permanently): the system dialog won't reappear, so open settings.
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
        if (requestCode != PERMISSION_REQUEST_CODE) {
            return;
        }
        if (hasAllPermissions()) {
            List<String> doorMacs = Prefs.parseDoorMacsText(doorMacInput.getText().toString());
            String phoneNumber = phoneNumberInput.getText().toString().trim();
            finishEnabling(doorMacs, phoneNumber);
        } else {
            toast(R.string.error_permission_denied);
            setSwitchChecked(false);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SCAN_NEARBY_REQUEST_CODE || resultCode != RESULT_OK || data == null) {
            return;
        }
        String selectedMac = data.getStringExtra(ScanDoorListActivity.EXTRA_SELECTED_MAC);
        if (selectedMac == null) {
            return;
        }
        List<String> current = Prefs.parseDoorMacsText(doorMacInput.getText().toString());
        if (!current.contains(selectedMac)) {
            current.add(selectedMac);
            doorMacInput.setText(String.join("\n", current));
            toast(R.string.toast_door_added);
        }
    }

    private boolean hasAllPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private boolean isBluetoothOn() {
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        return adapter != null && adapter.isEnabled();
    }

    private void setSwitchChecked(boolean checked) {
        suppressToggleCallback = true;
        enabledSwitch.setChecked(checked);
        suppressToggleCallback = false;
    }

    private void updateEditableState() {
        boolean editable = !Prefs.isEnabled(this);
        doorMacInput.setEnabled(editable);
        phoneNumberInput.setEnabled(editable);
        scanNearbyButton.setEnabled(editable);
    }

    private void updateStatusText() {
        statusText.setText(Prefs.isEnabled(this) ? R.string.status_running : R.string.status_stopped);
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_LONG).show();
    }
}
