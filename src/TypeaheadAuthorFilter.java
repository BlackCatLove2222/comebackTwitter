package app.morphe.extension.newx.postfilter;

import app.morphe.extension.newx.timeline.ZombieSettings;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JP jp24.13.15: hides designated authors ({@link BlockedAuthorsStore})
 * from search typeahead results.
 *
 * <p>Every typeahead operation funnels through the app-side repository
 * {@code com.x.repositories.typeahead.f} (classes6), whose four user
 * bearing methods call {@link #filterMixed(List)} /
 * {@link #filterUsers(List)} on their mapped result list just before it
 * is wrapped as a success result. The judgement reuses the existing
 * assets only — the designated-author toggle
 * ({@code newx.content.zombie_filtering.blocked_authors}, default ON)
 * and the store's numeric-ID snapshot — so no new setting or storage is
 * introduced, and the semantics match the timeline-side hiding exactly.
 *
 * <p>App model classes are reached reflectively, the JP bridge rule:
 * a mixed-list element is a user item only when its class is
 * {@code com.x.models.search.a1}, whose {@code a()} yields the
 * MinimalUser ({@code com.x.models.g5}); its ID comes from
 * {@code getId()} → field {@code a} (the {@code gh} numeric ID), the
 * same value the timeline filter compares against the store. Query
 * suggestions and any other element type pass through untouched.
 *
 * <p>Fail-open throughout: any error returns the input list, an element
 * whose ID cannot be resolved is kept, and when nothing is dropped the
 * input list object itself is returned. Candidate hiding is not
 * recorded in the filtered-replies store (it is not a reply review
 * surface), so no reason codes exist here.
 */
public final class TypeaheadAuthorFilter {
    private static final String USER_ITEM_CLASS = "com.x.models.search.a1";
    private static final String USER_ITEM_ACCESSOR = "a";
    private static final String ID_METHOD = "getId";
    private static final String ID_FIELD = "a";

    private TypeaheadAuthorFilter() {
    }

    /** Mixed suggestion list (search box): user items judged, the rest pass. */
    public static List<?> filterMixed(List<?> items) {
        try {
            return filterMixed(items, ZombieSettings.blockedAuthorsEnabled(),
                    BlockedAuthorsStore.shared().snapshot());
        } catch (Throwable ignored) {
            return items;
        }
    }

    /** User-only suggestion list (mentions, people pickers, payments). */
    public static List<?> filterUsers(List<?> users) {
        try {
            return filterUsers(users, ZombieSettings.blockedAuthorsEnabled(),
                    BlockedAuthorsStore.shared().snapshot());
        } catch (Throwable ignored) {
            return users;
        }
    }

    /**
     * Core of {@link #filterMixed(List)} with the judgement inputs made
     * explicit, so the JVM harness can drive it without the settings
     * framework. Returns the input object itself when nothing is dropped.
     */
    static List<?> filterMixed(List<?> items, boolean enabled, Set<String> blockedIds) {
        if (!enabled || blockedIds == null || blockedIds.isEmpty()
                || items == null || items.isEmpty()) {
            return items;
        }
        List<Object> kept = null;
        for (int index = 0; index < items.size(); index++) {
            Object item = items.get(index);
            if (isDesignatedUserItem(item, blockedIds)) {
                if (kept == null) {
                    kept = new ArrayList<>(items.subList(0, index));
                }
            } else if (kept != null) {
                kept.add(item);
            }
        }
        return kept == null ? items : kept;
    }

    /** Core of {@link #filterUsers(List)}; same contract as the mixed core. */
    static List<?> filterUsers(List<?> users, boolean enabled, Set<String> blockedIds) {
        if (!enabled || blockedIds == null || blockedIds.isEmpty()
                || users == null || users.isEmpty()) {
            return users;
        }
        List<Object> kept = null;
        for (int index = 0; index < users.size(); index++) {
            Object user = users.get(index);
            String authorId = userIdOf(user);
            if (authorId != null && blockedIds.contains(authorId)) {
                if (kept == null) {
                    kept = new ArrayList<>(users.subList(0, index));
                }
            } else if (kept != null) {
                kept.add(user);
            }
        }
        return kept == null ? users : kept;
    }

    /** True when a mixed-list element is a user item of a designated author. */
    private static boolean isDesignatedUserItem(Object item, Set<String> blockedIds) {
        try {
            if (item == null || !item.getClass().getName().equals(USER_ITEM_CLASS)) {
                return false;
            }
            Method accessor = findNoArgMethod(item.getClass(), USER_ITEM_ACCESSOR);
            if (accessor == null) {
                return false;
            }
            String authorId = userIdOf(accessor.invoke(item));
            return authorId != null && blockedIds.contains(authorId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Numeric author ID of a MinimalUser-shaped object, or null when it
     * cannot be resolved. Mirrors TimelineModelAccess.authorIdOfCanonical:
     * {@code getId()} → the id object's field {@code a} as a Number →
     * longValue; falls back to the id object's string form.
     */
    static String userIdOf(Object user) {
        try {
            if (user == null) {
                return null;
            }
            Method getId = findNoArgMethod(user.getClass(), ID_METHOD);
            if (getId == null) {
                return null;
            }
            Object id = getId.invoke(user);
            if (id == null) {
                return null;
            }
            Field numeric = findField(id.getClass(), ID_FIELD);
            if (numeric != null) {
                Object value = numeric.get(id);
                if (value instanceof Number) {
                    return String.valueOf(((Number) value).longValue());
                }
            }
            return id.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findNoArgMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // Try the superclass.
            }
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Try the superclass.
            }
        }
        return null;
    }
}
