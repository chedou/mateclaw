package vip.mate.channel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.channel.model.ChannelSessionEntity;
import vip.mate.channel.repository.ChannelSessionMapper;
import vip.mate.workspace.conversation.event.ConversationDeletedEvent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Covers the shared channel-session identity and the cache/database recovery
 * invariants used by normal channel routing and troubleshooting pre-routes.
 */
class ChannelSessionStoreTest {

    private ChannelSessionMapper mapper;
    private ChannelSessionStore store;

    @BeforeEach
    void setUp() {
        mapper = mock(ChannelSessionMapper.class);
        store = new ChannelSessionStore(mapper);
    }

    @Test
    void buildsTheSingleCanonicalConversationKeyForRouterAndPreRoutes() {
        assertEquals(
                "wecom:99:group-1",
                ChannelSessionStore.conversationId("wecom", 99L, "group-1"));
        assertEquals(
                "wecom:group-1",
                ChannelSessionStore.conversationId("wecom", null, "group-1"));
    }

    @Test
    void exactCacheMissReadsThroughDatabaseAndBackfillsLocalLeaderCache() {
        ChannelSessionEntity persisted = new ChannelSessionEntity();
        persisted.setConversationId("wecom:99:group-1");
        persisted.setChannelType("wecom");
        persisted.setChannelId(99L);
        persisted.setTargetId("group-1");
        when(mapper.selectOne(any())).thenReturn(persisted);

        assertSame(persisted, store.getSession("wecom:99:group-1"));
        assertSame(persisted, store.getSession("wecom:99:group-1"));

        verify(mapper, times(1)).selectOne(any());
    }

    @Test
    @DisplayName("deleting the conversation evicts the cached session")
    void conversationDeleteEvictsCache() {
        when(mapper.selectOne(any())).thenReturn(null);
        store.saveOrUpdate("dingtalk:1:alice", "dingtalk", "hook-1", "alice", "Alice", 1L);
        assertNotNull(store.getSession("dingtalk:1:alice"));

        store.onConversationDeleted(new ConversationDeletedEvent("dingtalk:1:alice"));

        assertNull(store.getSession("dingtalk:1:alice"),
                "a phantom entry would keep updating a row that no longer exists");
    }

    @Test
    @DisplayName("a cached entry pointing at a deleted row self-heals into a fresh insert")
    void staleCacheEntryIsRecreated() {
        when(mapper.selectOne(any())).thenReturn(null);
        store.saveOrUpdate("dingtalk:1:alice", "dingtalk", "hook-1", "alice", "Alice", 1L);
        verify(mapper, times(1)).insert(any(ChannelSessionEntity.class));

        // The row is deleted behind our back (console delete on another node,
        // or an event listener that never ran) — updateById now affects 0 rows.
        when(mapper.updateById(any(ChannelSessionEntity.class))).thenReturn(0);

        store.saveOrUpdate("dingtalk:1:alice", "dingtalk", "hook-2", "alice", "Alice", 1L);

        // Must not stop at the failed update: re-insert so proactive push and
        // cron channel resolution keep working.
        verify(mapper, times(2)).insert(any(ChannelSessionEntity.class));
        assertEquals("hook-2", store.getTargetId("dingtalk:1:alice"));
    }

    @Test
    @DisplayName("a successful update does not fall through to an insert")
    void liveRowIsUpdatedInPlace() {
        when(mapper.selectOne(any())).thenReturn(null);
        store.saveOrUpdate("dingtalk:1:alice", "dingtalk", "hook-1", "alice", "Alice", 1L);
        when(mapper.updateById(any(ChannelSessionEntity.class))).thenReturn(1);

        store.saveOrUpdate("dingtalk:1:alice", "dingtalk", "hook-2", "alice", "Alice", 1L);

        verify(mapper, times(1)).insert(any(ChannelSessionEntity.class));
        assertEquals("hook-2", store.getTargetId("dingtalk:1:alice"));
    }

    @Test
    @DisplayName("remove() clears both layers")
    void removeClearsCacheAndRow() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.delete(any())).thenReturn(1);
        store.saveOrUpdate("dingtalk:1:alice", "dingtalk", "hook-1", "alice", "Alice", 1L);

        assertEquals(1, store.remove("dingtalk:1:alice"));
        assertNull(store.getSession("dingtalk:1:alice"));
        verify(mapper).delete(any());
    }
}
