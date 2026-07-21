package vip.mate.loop.dto;

public record LoopSuperpowerPreviewRequest(
        String repoPath,
        String command,
        String goal
) {
}
