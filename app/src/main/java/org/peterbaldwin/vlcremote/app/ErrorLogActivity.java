package org.peterbaldwin.vlcremote.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.List;
import org.peterbaldwin.client.android.vlcremote.R;
import org.peterbaldwin.vlcremote.model.ErrorLog;

/**
 * Shows the in-memory {@link ErrorLog}: every error that popped a toast, newest first, with its
 * timestamp and full stacktrace. Cleared from the menu; the log is empty again after a restart.
 */
public class ErrorLogActivity extends Activity {

    private static final int MENU_CLEAR = 1;

    private ListView mList;
    private TextView mEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.pref_error_log_title);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        mEmpty = new TextView(this);
        mEmpty.setText(R.string.error_log_empty);
        mEmpty.setGravity(Gravity.CENTER);
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24,
                getResources().getDisplayMetrics());
        mEmpty.setPadding(pad, pad, pad, pad);

        mList = new ListView(this);
        mList.setEmptyView(mEmpty);

        root.addView(mEmpty, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(mList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        mList.setAdapter(new EntryAdapter(ErrorLog.snapshot()));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, MENU_CLEAR, Menu.NONE, R.string.error_log_clear);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_CLEAR) {
            ErrorLog.clear();
            refresh();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private final class EntryAdapter extends ArrayAdapter<ErrorLog.Entry> {
        EntryAdapter(List<ErrorLog.Entry> items) {
            super(ErrorLogActivity.this, 0, items);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv = (TextView) convertView;
            if (tv == null) {
                tv = new TextView(ErrorLogActivity.this);
                int p = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12,
                        getResources().getDisplayMetrics());
                tv.setPadding(p, p, p, p);
                tv.setTextIsSelectable(true);
            }
            ErrorLog.Entry e = getItem(position);
            CharSequence time = DateFormat.format("yyyy-MM-dd HH:mm:ss", e.getTimeMillis());
            StringBuilder sb = new StringBuilder();
            sb.append(time).append("  ").append(e.getMessage());
            if (e.getCount() > 1) {
                sb.append("  (×").append(e.getCount()).append(')');
            }
            if (e.getDetail() != null && !e.getDetail().isEmpty()) {
                sb.append('\n').append(e.getDetail());
            }
            tv.setText(sb.toString());
            tv.setTextColor(Color.LTGRAY);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            return tv;
        }
    }
}
