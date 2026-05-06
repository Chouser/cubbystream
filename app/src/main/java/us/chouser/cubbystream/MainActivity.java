package com.baseballstream;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class MainActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private StreamAdapter adapter;
    private ProgressBar progressBar;
    private TextView textStatus;
    private Button btnReload;

    private final FeedFetcher fetcher = new FeedFetcher();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recyclerView = findViewById(R.id.recycler_streams);
        progressBar  = findViewById(R.id.progress_bar);
        textStatus   = findViewById(R.id.text_status);
        btnReload    = findViewById(R.id.btn_reload);

        adapter = new StreamAdapter(item -> {
            // Launch the player with this stream
            Intent intent = new Intent(this, PlayerActivity.class);
            intent.putExtra(PlayerActivity.EXTRA_URL,      item.getUrl());
            intent.putExtra(PlayerActivity.EXTRA_TITLE,    item.getTitle());
            intent.putExtra(PlayerActivity.EXTRA_SUBTITLE, item.getSubtitle());
            intent.putExtra(PlayerActivity.EXTRA_TYPE,     item.getType());
            startActivity(intent);
        });

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.addItemDecoration(
                new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        recyclerView.setAdapter(adapter);

        btnReload.setOnClickListener(v -> loadFeed());

        // Load on first open
        loadFeed();
    }

    private void loadFeed() {
        setLoading(true);
        textStatus.setVisibility(View.GONE);

        fetcher.fetch(new FeedFetcher.Callback() {
            @Override
            public void onSuccess(StreamFeed feed) {
                setLoading(false);
                if (feed.getStreams().isEmpty()) {
                    showStatus("No streams available right now.");
                } else {
                    adapter.setItems(feed.getStreams());
                    String updated = feed.getUpdated() != null
                            ? "Updated: " + feed.getUpdated() : "";
                    if (!updated.isEmpty()) {
                        Toast.makeText(MainActivity.this, updated, Toast.LENGTH_SHORT).show();
                    }
                }
            }

            @Override
            public void onError(String message) {
                setLoading(false);
                showStatus("Could not load streams:\n" + message);
            }
        });
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnReload.setEnabled(!loading);
        recyclerView.setVisibility(loading ? View.GONE : View.VISIBLE);
    }

    private void showStatus(String message) {
        textStatus.setText(message);
        textStatus.setVisibility(View.VISIBLE);
    }
}
