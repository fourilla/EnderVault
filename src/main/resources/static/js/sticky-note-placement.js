export const NOTE_MARGIN = 8;
export const MAX_NOTE_Y = 1_000_000;

const clamp = (value, minimum, maximum) => Math.max(minimum, Math.min(maximum, value));
const travel = (containerWidth, noteWidth) => Math.max(0, containerWidth - noteWidth - 2 * NOTE_MARGIN);

export function projectNotePosition(note, container, noteWidth) {
    const horizontalTravel = travel(container.width, noteWidth);
    return {
        x: note.xRatio == null
                ? clamp(note.x, NOTE_MARGIN, NOTE_MARGIN + horizontalTravel)
                : NOTE_MARGIN + note.xRatio * horizontalTravel,
        y: note.xRatio == null
                ? Math.max(0, note.y - container.documentTop)
                : note.y
    };
}

export function pagePlacement(x, y, containerWidth, noteWidth, previousRatio = 0) {
    const horizontalTravel = travel(containerWidth, noteWidth);
    const left = clamp(x, NOTE_MARGIN, NOTE_MARGIN + horizontalTravel);
    return {
        x: Math.round(left),
        y: Math.round(clamp(y, 0, MAX_NOTE_Y)),
        xRatio: horizontalTravel > 0 ? (left - NOTE_MARGIN) / horizontalTravel : previousRatio
    };
}

export function defaultNotePlacement(container, viewport, noteSize, offset = 0) {
    const visibleTop = Math.max(viewport.top, container.top);
    const minimumTop = visibleTop + NOTE_MARGIN;
    const maximumTop = Math.max(minimumTop, viewport.bottom - noteSize.height - NOTE_MARGIN);
    const top = clamp(visibleTop + 24 + offset, minimumTop, maximumTop);
    return pagePlacement(24 + offset, top - container.top, container.width, noteSize.width);
}

export function edgeScrollDelta(pointerY, top, bottom, elapsedMs) {
    const edge = 48;
    const above = clamp((top + edge - pointerY) / edge, 0, 1);
    const below = clamp((pointerY - bottom + edge) / edge, 0, 1);
    return (below - above) * 600 * Math.min(32, Math.max(0, elapsedMs)) / 1000;
}
