package soloMapling.server.EventMessageSystem;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/*
Central hub for all event publishing/subscribing
Manages subscriber registry
Handles event distribution to subscribers
 */

public class EventBus {
    private static class Holder { private static final EventBus INSTANCE = new EventBus(); }
    private final Map<EventType, List<EventSubscriber>> subscribers;
    private final EventStore eventStore;

    private EventBus() {
        this.subscribers = new ConcurrentHashMap<>();
        this.eventStore = new EventStore(5000); // Keep last 5000 events
    }

    public static EventBus getInstance() {
        return Holder.INSTANCE;
    }

    public void publish(GameEvent event) {
        // Store in event store for historical queries
        eventStore.add(event);

        // Notify subscribers
        List<EventSubscriber> interestedSubs = subscribers.get(event.getType());
        if (interestedSubs != null) {
            for (EventSubscriber subscriber : interestedSubs) {
                try {
                    if (subscriber.matchesFilter(event)) subscriber.onEvent(event);
                } catch (RuntimeException e) {
                    org.slf4j.LoggerFactory.getLogger(EventBus.class)
                            .warn("SoloMapling event subscriber failed for {}", event.getType(), e);
                }
            }
        }
    }

    public void subscribe(EventType type, EventSubscriber subscriber) {
        subscribers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>())
                .add(subscriber);
    }

    public void unsubscribe(EventType type, EventSubscriber subscriber) {
        List<EventSubscriber> subs = subscribers.get(type);
        if (subs != null) {
            subs.remove(subscriber);
        }
    }

    public void unsubscribeAll(EventSubscriber subscriber) {
        // Just remove from all event types - brute force but simple
        for (EventType type : EventType.values()) {
            List<EventSubscriber> subs = subscribers.get(type);
            if (subs != null) {
                subs.remove(subscriber);
            }
        }
    }

    public EventStore getEventStore() {
        return eventStore;
    }
}
