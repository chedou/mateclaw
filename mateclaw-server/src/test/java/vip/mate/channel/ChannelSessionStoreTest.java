package vip.mate.channel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.channel.model.ChannelSessionEntity;
import vip.mate.channel.repository.ChannelSessionMapper;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChannelSessionStoreTest {

    @Mock private ChannelSessionMapper mapper;

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
        ChannelSessionStore store = new ChannelSessionStore(mapper);

        assertSame(persisted, store.getSession("wecom:99:group-1"));
        assertSame(persisted, store.getSession("wecom:99:group-1"));

        verify(mapper, times(1)).selectOne(any());
    }
}
