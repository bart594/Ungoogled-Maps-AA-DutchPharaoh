package patches.maps.gearslip;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Live suggestion rows under the focused editor's scene_card.
 *
 * <p>Reads only visible resource names, widget classes and truthful labels from Maps' own
 * projected hierarchy; never invents a label (unlabeled rows are omitted) and never logs one.
 * Refreshes only while the native editor is active: layout/pre-draw observation plus bounded
 * delayed passes after each input edit, all cancelled when the editor closes.
 */
final class NativeSuggestions implements ViewTreeObserver.OnGlobalLayoutListener, ViewTreeObserver.OnPreDrawListener {
    static final int MAX_ROWS = 6;
    static final String EDITOR_ID = "destination_input_keyboard_search_edit_text";
    static final String LIST_ID = "recycler_view";
    private static final int MAX_VISITS = 2048;
    private static final int MAX_DEPTH = 48;
    private static final int MAX_TEXTS = 8;
    private static final int MAX_LINES = 2;
    private static final int MAX_ROW_VISITS = 256;

    /** One published row. Identity is editor + view + container + index + label + lines. */
    static final class Entry {
        private final WeakReference<NativeSuggestions> owner;
        private final WeakReference<View> editor;
        private final WeakReference<View> row;
        private final WeakReference<View> click;
        private final WeakReference<ViewGroup> container;
        private final int index;
        private final String label;
        private final List<String> lines;
        private final long generation;

        Entry(NativeSuggestions owner, View editor, View row, View click, ViewGroup container, int index,
                String label, List<String> lines, long generation) {
            this.owner = new WeakReference<>(owner);
            this.editor = new WeakReference<>(editor);
            this.row = new WeakReference<>(row);
            this.click = new WeakReference<>(click);
            this.container = new WeakReference<>(container);
            this.index = index;
            this.label = label;
            this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
            this.generation = generation;
        }

        public String label() { return label; }
        public List<String> lines() { return lines; }

        /**
         * Clicks only the row's own original listener after revalidating the current editor,
         * the current visible recycler, attachment, enablement and the exact label and lines.
         * Anything else is stale and never acts.
         *
         * <p>Limitation: a recycled view rebound to the same label and lines at the same index
         * cannot be distinguished through public APIs. Entries retire on every input edit and
         * on keyboard/watcher replacement to bound that window; report if an identical visible
         * binding cannot be proved distinct.
         */
        public boolean revalidateAndInvoke() {
            NativeSuggestions watcher = owner.get();
            View rowView = row.get();
            ViewGroup parent = container.get();
            View target = click.get();
            View editorView = editor.get();
            if (watcher == null || rowView == null || parent == null || target == null || editorView == null) return false;
            if (watcher.closed || generation != watcher.generation) return false;
            if (!rowView.isAttachedToWindow() || rowView.getParent() != parent) return false;
            if (parent.indexOfChild(rowView) != index) return false;
            if (!rowView.isShown() || rowView.getWidth() <= 0 || !rowView.isEnabled()) return false;
            if (target != rowView || !target.isShown() || !target.isEnabled()) return false;
            if (!editorView.isAttachedToWindow() || watcher.currentEditor() != editorView) return false;
            View scene = ancestorWith(rowView, "scene_card");
            if (scene == null || findList(scene) != parent) return false;
            List<String> texts = readTexts(rowView);
            if (!label.equals(labelOf(rowView, texts))) return false;
            List<String> current = texts.size() > 1
                    ? texts.subList(1, Math.min(texts.size(), 1 + MAX_LINES)) : Collections.emptyList();
            if (!lines.equals(current)) return false;
            return target.performClick();
        }

        boolean sameBinding(Entry other) {
            return index == other.index && label.equals(other.label) && lines.equals(other.lines)
                    && row.get() == other.row.get() && container.get() == other.container.get()
                    && editor.get() != null && editor.get() == other.editor.get();
        }
    }

    private final ViewGroup root;
    private final Runnable changed;
    private boolean closed;
    private long generation;
    private List<Entry> published = Collections.emptyList();
    private List<Entry> retained = Collections.emptyList();
    private List<Entry> lastSeen = Collections.emptyList();
    private boolean awaiting;
    private boolean pending;

    NativeSuggestions(ViewGroup root, Runnable changed) {
        this.root = root;
        this.changed = changed;
        root.getViewTreeObserver().addOnGlobalLayoutListener(this);
        root.getViewTreeObserver().addOnPreDrawListener(this);
        pass();
    }

    @Override public void onGlobalLayout() { if (!closed) coalesce(); }
    @Override public boolean onPreDraw() { if (!closed) coalesce(); return true; }

    private void coalesce() {
        if (closed || pending) return;
        pending = true;
        root.post(() -> { pending = false; pass(); });
    }

    /**
     * Retires prior entries on input edit: the display clears and stale actions go inert
     * immediately, while the previous snapshot is retained only as a comparison. Unchanged
     * pre-edit rows are not republished until the native list actually changes; older delayed
     * passes self-cancel against a newer generation and nothing outlives the editor.
     */
    void queryChanged() {
        if (closed) return;
        generation++;
        retained = lastSeen;
        awaiting = true;
        final long current = generation;
        if (!published.isEmpty()) { published = Collections.emptyList(); changed.run(); }
        root.postDelayed(() -> { if (!closed && current == generation) pass(); }, 400);
        root.postDelayed(() -> { if (!closed && current == generation) pass(); }, 1200);
    }

    List<Entry> snapshot() { return published; }

    void close() {
        if (closed) return;
        closed = true;
        if (root.getViewTreeObserver().isAlive()) {
            root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            root.getViewTreeObserver().removeOnPreDrawListener(this);
        }
        published = Collections.emptyList();
    }

    private void pass() {
        if (closed) return;
        List<Entry> next = collect();
        lastSeen = next;
        if (awaiting) {
            if (next.isEmpty() || sameEntries(next, retained)) return;
            awaiting = false;
        }
        if (!sameEntries(published, next)) { published = next; changed.run(); }
    }

    private static boolean sameEntries(List<Entry> before, List<Entry> after) {
        if (before.size() != after.size()) return false;
        for (int i = 0; i < before.size(); i++) {
            if (!before.get(i).sameBinding(after.get(i))) return false;
        }
        return true;
    }

    View currentEditor() { return findEditor(root); }

    private List<Entry> collect() {
        View editor = findEditor(root);
        if (editor == null) return Collections.emptyList();
        View scene = ancestorWith(editor, "scene_card");
        if (scene == null) return Collections.emptyList();
        View list = findList(scene);
        if (!(list instanceof ViewGroup)) return Collections.emptyList();
        ViewGroup group = (ViewGroup) list;
        List<Entry> rows = new ArrayList<>();
        for (int i = 0; i < group.getChildCount() && rows.size() < MAX_ROWS; i++) {
            View row = group.getChildAt(i);
            if (row == null || !row.isShown() || row.getWidth() <= 0 || row.getHeight() <= 0) continue;
            // Only the row's own original listener ever acts; never guess a descendant target.
            if (!row.isClickable() && !row.hasOnClickListeners()) continue;
            List<String> texts = readTexts(row);
            String label = labelOf(row, texts);
            if (label.isEmpty()) continue;
            List<String> lines = texts.size() > 1
                    ? new ArrayList<>(texts.subList(1, Math.min(texts.size(), 1 + MAX_LINES)))
                    : Collections.emptyList();
            rows.add(new Entry(this, editor, row, row, group, i, label, lines, generation));
        }
        return rows;
    }

    private static String labelOf(View row, List<String> texts) {
        if (!texts.isEmpty()) return texts.get(0);
        CharSequence description = row.getContentDescription();
        if (description != null) {
            String fallback = description.toString().trim();
            if (!fallback.isEmpty()) return fallback;
        }
        return "";
    }

    static String readLabel(View row) {
        return labelOf(row, readTexts(row));
    }

    private static List<String> readTexts(View row) {
        List<String> texts = new ArrayList<>();
        java.util.ArrayDeque<View> stack = new java.util.ArrayDeque<>();
        stack.push(row);
        int visits = 0;
        while (!stack.isEmpty() && visits < MAX_ROW_VISITS && texts.size() < MAX_TEXTS) {
            View view = stack.pop();
            visits++;
            if (view instanceof TextView && view.isShown()) {
                CharSequence content = ((TextView) view).getText();
                if (content != null) {
                    String text = content.toString().trim();
                    if (!text.isEmpty() && !texts.contains(text)) texts.add(text);
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = group.getChildCount() - 1; i >= 0; i--) {
                    View child = group.getChildAt(i);
                    if (child != null) stack.push(child);
                }
            }
        }
        return texts;
    }

    private static View findEditor(View root) {
        java.util.ArrayDeque<View> stack = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<Integer> depths = new java.util.ArrayDeque<>();
        stack.push(root); depths.push(0);
        View fallback = null;
        int visits = 0;
        while (!stack.isEmpty() && visits < MAX_VISITS) {
            View view = stack.pop();
            int depth = depths.pop();
            visits++;
            if (EDITOR_ID.equals(name(view)) && view.isShown()) {
                if (view.isFocused()) return view;
                if (fallback == null) fallback = view;
            }
            if (view instanceof ViewGroup && depth < MAX_DEPTH) {
                ViewGroup group = (ViewGroup) view;
                for (int i = group.getChildCount() - 1; i >= 0; i--) {
                    View child = group.getChildAt(i);
                    if (child != null) { stack.push(child); depths.push(depth + 1); }
                }
            }
        }
        return fallback;
    }

    private static View findList(View scene) {
        java.util.ArrayDeque<View> stack = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<Integer> depths = new java.util.ArrayDeque<>();
        stack.push(scene); depths.push(0);
        int visits = 0;
        while (!stack.isEmpty() && visits < MAX_VISITS) {
            View view = stack.pop();
            int depth = depths.pop();
            visits++;
            if (view != scene && LIST_ID.equals(name(view)) && isListKind(view) && view.isShown()
                    && view.getWidth() > 0 && view.getHeight() > 0) return view;
            if (view instanceof ViewGroup && depth < MAX_DEPTH) {
                ViewGroup group = (ViewGroup) view;
                for (int i = group.getChildCount() - 1; i >= 0; i--) {
                    View child = group.getChildAt(i);
                    if (child != null) { stack.push(child); depths.push(depth + 1); }
                }
            }
        }
        return null;
    }

    /** The measured list widget (or a platform list ancestor) plus the known id. */
    private static boolean isListKind(View view) {
        if (view instanceof android.widget.AdapterView) return true;
        for (Class<?> type = view.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            String name = type.getName();
            if (name.contains("CarRecyclerView") || name.contains("RecyclerView") || name.contains("AbsListView")
                    || name.contains("AdapterView") || name.contains("ListView") || name.contains("GridView")) return true;
        }
        return false;
    }

    private static View ancestorWith(View view, String fragment) {
        for (android.view.ViewParent node = view.getParent(); node instanceof View; node = node.getParent()) {
            String entry = name((View) node);
            if (entry != null && entry.toLowerCase(Locale.US).contains(fragment)) return (View) node;
        }
        return null;
    }

    private static String name(View view) {
        if ((view.getId() >>> 24) == 0 || view.getId() == View.NO_ID) return null;
        try { return view.getResources().getResourceEntryName(view.getId()); }
        catch (android.content.res.Resources.NotFoundException missing) { return null; }
        catch (RuntimeException missing) { return null; }
    }
}
