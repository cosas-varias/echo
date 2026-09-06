package com.copa.echo;

import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A branching questionnaire read from a JSON file in the assets, and the answers someone gave to
 * it.
 *
 * The tree lives in {@code assets/survey.json} rather than in code so the questions can be
 * reworded, reordered or rebranched without a build: {@link SurveyActivity} only knows about the
 * four kinds of node below and about following {@code next} from one to the next.
 *
 * <pre>
 * {
 *   "title": "...",
 *   "start": "id of the first node",
 *   "nodes": {
 *     "id": { "type": "choice", "question": "...",
 *             "options": [ { "label": "...", "next": "id" } ] },
 *     "id": { "type": "scale",  "question": "...", "min": 1, "max": 5,
 *             "minLabel": "...", "maxLabel": "...", "next": "id" },
 *     "id": { "type": "text",   "question": "...", "hint": "...", "next": "id" },
 *     "id": { "type": "end",    "question": "closing words" }
 *   }
 * }
 * </pre>
 *
 * A missing or null {@code next} ends the questionnaire, so an {@code end} node is only needed
 * when there is something left to say.
 */
public class Survey {

    private static final String TAG = Survey.class.getSimpleName();

    public static final String ASSET_NAME = "survey.json";

    public static final String TYPE_CHOICE = "choice";
    public static final String TYPE_SCALE = "scale";
    public static final String TYPE_TEXT = "text";
    public static final String TYPE_END = "end";

    public static class Option {
        public final String label;
        public final String next;

        Option(String label, String next) {
            this.label = label;
            this.next = next;
        }
    }

    public static class Node {
        public String id;
        public String type = TYPE_CHOICE;
        public String question = "";
        public String hint = "";
        public String next;
        public int min = 1;
        public int max = 5;
        public String minLabel = "";
        public String maxLabel = "";
        public final List<Option> options = new ArrayList<Option>();
    }

    /** One question as it was put, and the answer that was given to it. */
    public static class Answer {
        public final String id;
        public final String question;
        public final String answer;

        public Answer(String id, String question, String answer) {
            this.id = id;
            this.question = question;
            this.answer = answer;
        }
    }

    public final String title;
    public final String start;
    private final Map<String, Node> nodes;

    private Survey(String title, String start, Map<String, Node> nodes) {
        this.title = title;
        this.start = start;
        this.nodes = nodes;
    }

    public Node node(String id) {
        return id == null ? null : nodes.get(id);
    }

    /** Reads the questionnaire shipped with the app, or null when it cannot be read. */
    public static Survey load(AssetManager assets) {
        try {
            return parse(readAsset(assets, ASSET_NAME));
        } catch (Exception e) {
            Log.e(TAG, "Can't read " + ASSET_NAME, e);
            return null;
        }
    }

    static Survey parse(String json) throws JSONException {
        final JSONObject root = new JSONObject(json);
        final JSONObject nodesJson = root.getJSONObject("nodes");
        final Map<String, Node> nodes = new LinkedHashMap<String, Node>();

        final java.util.Iterator<String> keys = nodesJson.keys();
        while (keys.hasNext()) {
            final String id = keys.next();
            final JSONObject nodeJson = nodesJson.getJSONObject(id);
            final Node node = new Node();
            node.id = id;
            node.type = nodeJson.optString("type", TYPE_CHOICE);
            node.question = nodeJson.optString("question", "");
            node.hint = nodeJson.optString("hint", "");
            node.next = nodeJson.isNull("next") ? null : nodeJson.optString("next", null);
            node.min = nodeJson.optInt("min", 1);
            node.max = nodeJson.optInt("max", 5);
            node.minLabel = nodeJson.optString("minLabel", "");
            node.maxLabel = nodeJson.optString("maxLabel", "");

            final JSONArray options = nodeJson.optJSONArray("options");
            if (options != null) {
                for (int i = 0; i < options.length(); ++i) {
                    final JSONObject option = options.getJSONObject(i);
                    node.options.add(new Option(option.optString("label", ""),
                            option.isNull("next") ? null : option.optString("next", null)));
                }
            }
            nodes.put(id, node);
        }

        return new Survey(root.optString("title", ""), root.optString("start", ""), nodes);
    }

    /**
     * The answers as the JSON a survey trace holds: the questions travel with them, so a filled-in
     * survey can still be read after the tree in the assets has been reworded.
     */
    public String toJson(List<Answer> answers, long savedMillis) {
        final JSONObject root = new JSONObject();
        try {
            root.put("survey", title);
            root.put("saved", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
                    .format(new Date(savedMillis)));
            final JSONArray array = new JSONArray();
            for (Answer answer : answers) {
                final JSONObject item = new JSONObject();
                item.put("id", answer.id);
                item.put("question", answer.question);
                item.put("answer", answer.answer);
                array.put(item);
            }
            root.put("answers", array);
            return root.toString(2);
        } catch (JSONException e) {
            // Every value here is a string we just put in ourselves, so this cannot fail in
            // practice; losing the answers to it silently would be far worse than a raw dump.
            Log.e(TAG, "Can't build the survey JSON", e);
            return answers.toString();
        }
    }

    /** A filled-in survey as plain lines, for showing it on screen. Raw text if it will not parse. */
    public static String readable(String json) {
        try {
            final JSONObject root = new JSONObject(json);
            final StringBuilder out = new StringBuilder();
            final String saved = root.optString("saved", "");
            if (!saved.isEmpty()) out.append(saved).append("\n\n");
            final JSONArray answers = root.optJSONArray("answers");
            if (answers != null) {
                for (int i = 0; i < answers.length(); ++i) {
                    final JSONObject item = answers.getJSONObject(i);
                    out.append(item.optString("question", "")).append('\n');
                    out.append("→ ").append(item.optString("answer", "")).append("\n\n");
                }
            }
            return out.toString().trim();
        } catch (JSONException e) {
            return json;
        }
    }

    private static String readAsset(AssetManager assets, String name) throws IOException {
        final InputStream in = assets.open(name);
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }
}
