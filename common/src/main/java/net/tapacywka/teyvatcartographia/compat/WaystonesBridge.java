package net.tapacywka.teyvatcartographia.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The client events of Waystones ({@code net.blay09.mods.waystones.api.event}): the list of the player's stones of one
 * type (sent at login and when it changes), a stone changed, a stone removed. Read by reflection, so the mod needs no
 * Waystones to build and takes either API: Balm's event bus with getters (Waystones for 1.21.1:
 * {@code Balm.getEvents().onEvent(type, listener)}) or a static {@code EVENT} of each event record (Waystones for 26.x,
 * where the waystone's type is also named its kind).
 */
final class WaystonesBridge {
    private WaystonesBridge() {}

    private static final String EVENTS = "net.blay09.mods.waystones.api.event.";
    private static boolean registered;

    /** Listens to the three events once; does nothing without Waystones or when its API cannot be found. */
    static void register() {
        if (registered || !WaystonesCompat.loaded()) return;
        registered = true;
        try {
            listen(EVENTS + "WaystonesListReceivedEvent", WaystonesBridge::onList);
            listen(EVENTS + "WaystoneUpdateReceivedEvent", WaystonesBridge::onUpdate);
            listen(EVENTS + "WaystoneRemoveReceivedEvent", WaystonesBridge::onRemove);
            TeyvatCartographia.LOGGER.info("Waystones: its stones are shown on the maps");
        } catch (ReflectiveOperationException | RuntimeException e) {
            TeyvatCartographia.LOGGER.warn("Waystones is in the game but its events could not be reached; only the stones seen in the world show", e);
        }
    }

    /** The listener on the main thread (the events come with the network packets). */
    private static void listen(String eventClass, Consumer<Object> handler) throws ReflectiveOperationException {
        Class<?> type = Class.forName(eventClass);
        Consumer<Object> onMain = e -> Minecraft.getInstance().execute(() -> {
            try {
                handler.accept(e);
            } catch (RuntimeException ex) {
                TeyvatCartographia.LOGGER.warn("Waystones: an event could not be read", ex);
            }
        });
        // Waystones for 26.x: a static EVENT whose register takes a listener (a functional interface of its own).
        try {
            java.lang.reflect.Field field = type.getField("EVENT");
            Object event = field.get(null);
            // Through the field's declared (public) type: the object itself may be of a class that is not.
            for (Method m : field.getType().getMethods()) {
                if (!m.getName().equals("register") || m.getParameterCount() != 1 || !m.getParameterTypes()[0].isInterface()) continue;
                Class<?> listener = m.getParameterTypes()[0];
                Object proxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener}, (p, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return objectMethod(p, method, args);
                    if (args != null && args.length == 1) onMain.accept(args[0]);
                    return null;
                });
                m.invoke(event, proxy);
                return;
            }
        } catch (NoSuchFieldException ignored) {
            // Waystones for 1.21.1: Balm's event bus.
        }
        Class<?> balm = Class.forName("net.blay09.mods.balm.api.Balm");
        Object events = balm.getMethod("getEvents").invoke(null);
        events.getClass().getMethod("onEvent", Class.class, Consumer.class).invoke(events, type, onMain);
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> "WaystonesBridge listener";
        };
    }

    // ------------------------------------------------------------------ the events

    private static void onList(Object event) {
        String type = String.valueOf(get(event, "getWaystoneType", "waystoneType"));
        List<WaystonesCompat.Stone> stones = new ArrayList<>();
        for (Object w : (List<?>) get(event, "getWaystones", "waystones")) {
            WaystonesCompat.Stone s = stone(w);
            if (s != null) stones.add(s);
        }
        WaystonesCompat.listReceived(type, stones);
    }

    private static void onUpdate(Object event) {
        Object w = get(event, "getWaystone", "waystone");
        UUID id = (UUID) get(w, "getWaystoneUid");
        WaystonesCompat.updateReceived(id, typeOf(w), stone(w));
    }

    private static void onRemove(Object event) {
        WaystonesCompat.removeReceived((UUID) get(event, "getWaystoneId", "waystoneId"));
    }

    /** A Waystones waystone as a stone of the map, or null for one this map does not show (or a passing one). */
    @SuppressWarnings("unchecked")
    private static WaystonesCompat.Stone stone(Object w) {
        WaystonesCompat.Kind kind = WaystonesCompat.kindOfType(typeOf(w));
        if (kind == null || Boolean.TRUE.equals(get(w, "isTransient"))) return null;
        Object origin = get(w, "getOrigin");
        boolean dungeon = origin != null && origin.toString().equalsIgnoreCase("dungeon");
        Component name = (Component) get(w, "getEffectiveName", "getName");
        return new WaystonesCompat.Stone((ResourceKey<Level>) get(w, "getDimension"), ((BlockPos) get(w, "getPos")).immutable(), kind,
                dungeon, (UUID) get(w, "getWaystoneUid"), name == null ? null : name.getString());
    }

    /** The waystone's type: "waystones:waystone", "waystones:blue_sharestone" ... (named its kind in 26.x). */
    private static String typeOf(Object w) {
        return String.valueOf(get(w, "getWaystoneType", "getWaystoneKind"));
    }

    /**
     * The first of these no-argument methods {@code target} has, called through a public type that declares it (a
     * waystone's own class may not be public; its interface {@code api.Waystone} is).
     */
    private static Object get(Object target, String... names) {
        for (String n : names) {
            Method m = publicMethod(target.getClass(), n);
            if (m == null) continue;
            try {
                return m.invoke(target);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Waystones: " + n + " failed", e);
            }
        }
        throw new IllegalStateException("Waystones: none of " + String.join(", ", names) + " on " + target.getClass().getName());
    }

    private static Method publicMethod(Class<?> c, String name) {
        if (c == null) return null;
        if (java.lang.reflect.Modifier.isPublic(c.getModifiers())) {
            try {
                return c.getMethod(name);
            } catch (NoSuchMethodException ignored) {
                return null;
            }
        }
        for (Class<?> i : c.getInterfaces()) {
            Method m = publicMethod(i, name);
            if (m != null) return m;
        }
        return publicMethod(c.getSuperclass(), name);
    }
}
