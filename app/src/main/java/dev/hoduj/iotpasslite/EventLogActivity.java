package dev.hoduj.iotpasslite;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.Window;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shows every step EventLog has recorded, newest first. Tapping a row shows the full detail —
 *  this is how you check "did it actually try to open the door just now, and did it work?"
 *  without needing adb or a computer at all. */
public class EventLogActivity extends Activity {

    private static final SimpleDateFormat ROW_FORMAT =
            new SimpleDateFormat("MM-dd HH:mm", Locale.KOREA);
    private static final SimpleDateFormat DETAIL_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.KOREA);

    private List<EventLog.Entry> entries = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_event_log);

        findViewById(R.id.log_clear_button).setOnClickListener(v -> {
            EventLog.clear(this);
            refresh();
        });

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        entries = EventLog.recent(this);

        ListView listView = findViewById(R.id.log_list);
        listView.setAdapter(new LogAdapter());
        listView.setOnItemClickListener((parent, view, position, id) -> showDetail(entries.get(position)));

        findViewById(R.id.log_empty_text).setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private final class LogAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView
                    : getLayoutInflater().inflate(R.layout.item_log, parent, false);
            EventLog.Entry entry = entries.get(position);
            ((TextView) row.findViewById(R.id.log_title)).setText(typeLabel(entry));
            ((TextView) row.findViewById(R.id.log_time)).setText(ROW_FORMAT.format(entry.timestampMillis));
            TextView sub = row.findViewById(R.id.log_sub);
            String subText = entry.doorMac == null ? entry.detail : entry.doorMac;
            sub.setText(subText);
            sub.setVisibility(subText == null ? View.GONE : View.VISIBLE);
            row.findViewById(R.id.log_dot).setBackgroundColor(getColor(colorFor(entry.type)));
            return row;
        }
    }

    private static int colorFor(EventLog.Type type) {
        switch (type) {
            case DOOR_OPEN:
                return R.color.ok;
            case ADVERTISE_FAIL:
            case REARM_FAILED:
            case BLUETOOTH_OFF:
                return R.color.fail;
            default:
                return R.color.primary;
        }
    }

    private String typeLabel(EventLog.Entry entry) {
        int resId = getResources().getIdentifier(
                "log_type_" + entry.type.name(), "string", getPackageName());
        return resId != 0 ? getString(resId) : entry.type.name();
    }

    private void showDetail(EventLog.Entry entry) {
        Dialog dialog = new Dialog(this, R.style.Theme_IoTPassLite_SheetDialog);
        dialog.setContentView(R.layout.dialog_log_detail);
        ((TextView) dialog.findViewById(R.id.detail_title)).setText(typeLabel(entry));
        dialog.findViewById(R.id.detail_dot).setBackgroundColor(getColor(colorFor(entry.type)));
        ((TextView) dialog.findViewById(R.id.detail_time)).setText(DETAIL_FORMAT.format(entry.timestampMillis));
        bind(dialog, R.id.detail_door_label, R.id.detail_door, entry.doorMac);
        bind(dialog, R.id.detail_info_label, R.id.detail_info, entry.detail);
        dialog.findViewById(R.id.detail_close).setOnClickListener(v -> dialog.dismiss());
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.show();
    }

    private static void bind(Dialog dialog, int labelId, int valueId, String value) {
        int vis = value == null ? View.GONE : View.VISIBLE;
        dialog.findViewById(labelId).setVisibility(vis);
        TextView tv = dialog.findViewById(valueId);
        tv.setVisibility(vis);
        tv.setText(value);
    }
}
