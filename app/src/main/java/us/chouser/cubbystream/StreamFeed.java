package com.baseballstream;

import java.util.List;

/**
 * Top-level JSON feed model.
 *
 * Expected JSON format:
 * {
 *   "updated": "2026-04-29T18:00:00Z",
 *   "streams": [
 *     {
 *       "title": "Yankees vs Red Sox",
 *       "subtitle": "ESPN Radio • 7:05 PM ET",
 *       "url": "https://example.com/stream1.m3u8",
 *       "type": "hls"          <-- optional
 *     },
 *     ...
 *   ]
 * }
 */
public class StreamFeed {
    private String updated;
    private List<StreamItem> streams;

    public String getUpdated() { return updated; }
    public List<StreamItem> getStreams() { return streams; }
}
