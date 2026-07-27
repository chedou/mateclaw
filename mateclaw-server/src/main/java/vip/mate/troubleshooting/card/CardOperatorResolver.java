package vip.mate.troubleshooting.card;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.auth.sso.model.ExternalIdentityEntity;
import vip.mate.auth.sso.repository.ExternalIdentityMapper;

import java.util.Optional;

/**
 * Maps a chat click to an accountable MateClaw operator.
 *
 * <p>A card gives us a channel-scoped id (a Feishu {@code open_id}), which is
 * not an identity the platform can hold responsible: it carries no role, no
 * capability and no audit trail. Recording a lifecycle transition against a raw
 * {@code open_id} would leave an approval nobody can trace to a person, which
 * defeats the point of requiring human approval at all.</p>
 *
 * <p>So this resolver only succeeds when the clicker has already linked that
 * chat account to a MateClaw user through SSO. An unlinked click resolves to
 * empty and the handler refuses to act — the card is a convenience, never a way
 * around identity.</p>
 */
@Component
public class CardOperatorResolver {

    private static final String PROVIDER_FEISHU = "feishu";

    private final ExternalIdentityMapper identityMapper;
    private final AuthService authService;

    public CardOperatorResolver(ExternalIdentityMapper identityMapper, AuthService authService) {
        this.identityMapper = identityMapper;
        this.authService = authService;
    }

    /**
     * Resolves the username to record on a transition.
     *
     * @param openId the clicker's Feishu open id
     * @return the linked MateClaw username, or empty when the account is not linked
     */
    public Optional<String> resolveFeishu(String openId) {
        if (openId == null || openId.isBlank()) {
            return Optional.empty();
        }
        ExternalIdentityEntity identity = identityMapper.selectOne(
                new LambdaQueryWrapper<ExternalIdentityEntity>()
                        .eq(ExternalIdentityEntity::getProvider, PROVIDER_FEISHU)
                        .eq(ExternalIdentityEntity::getExternalId, openId.trim())
                        .last("LIMIT 1"));
        if (identity == null || identity.getUserId() == null) {
            return Optional.empty();
        }
        UserEntity user = authService.findById(identity.getUserId());
        return user == null || user.getUsername() == null || user.getUsername().isBlank()
                ? Optional.empty()
                : Optional.of(user.getUsername());
    }
}
