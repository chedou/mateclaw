package vip.mate.troubleshooting.projection;

/**
 * One configuration layer that a system must have before evidence can be collected.
 *
 * <p>Stated as a gap rather than an error because an unonboarded system is not a
 * failure of the investigation — nothing was ever wired up for it. The reporter
 * cannot close these; {@code owner} says who can.
 */
public record SystemOnboardingGap(
        SystemOnboardingGapKind kind,
        String title,
        String detail,
        String owner) {

    public SystemOnboardingGap {
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        title = title == null ? "" : title.trim();
        detail = detail == null ? "" : detail.trim();
        owner = owner == null ? "" : owner.trim();
    }
}
