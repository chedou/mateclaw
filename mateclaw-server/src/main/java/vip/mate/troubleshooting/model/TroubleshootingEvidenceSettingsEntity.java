package vip.mate.troubleshooting.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Per-workspace evidence source settings.
 *
 * <p>One row per workspace, or none — absence means the workspace has never
 * been configured and the deployment yml still applies. That is what keeps an
 * existing install behaving exactly as before this table appeared.
 *
 * <p>{@code guanceApiKey} is the {@code enc:v1:} envelope produced by
 * {@code SettingCrypto}, never a plaintext key. Nothing outside
 * {@code WorkspaceEvidenceSettingsService} should read this field directly:
 * decryption, masking and the never-return-to-browser rule all live there.
 */
@Data
@TableName("mate_troubleshooting_evidence_settings")
public class TroubleshootingEvidenceSettingsEntity {

    @TableId
    private Long workspaceId;

    private Boolean guanceEnabled;
    private String guanceBaseUrl;
    private String guanceApiKey;
    private Boolean guanceAllowInsecureHttp;
    private Boolean replayEnabled;
    private Boolean agentEnabled;

    /** Optimistic lock; the writer must echo the version it read. */
    private Integer version;

    private String changedBy;
    private String changeReason;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
