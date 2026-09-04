import { useLayoutEffect, useRef, useState, type CSSProperties } from 'react';

type MarqueeStyle = CSSProperties & {
  '--settings-marquee-distance'?: string;
  '--settings-marquee-duration'?: string;
};

export function OverflowMarquee({ text }: { text: string }) {
  const viewportRef = useRef<HTMLSpanElement>(null);
  const trackRef = useRef<HTMLSpanElement>(null);
  const [distance, setDistance] = useState(0);

  useLayoutEffect(() => {
    const update = () => {
      const viewport = viewportRef.current;
      const track = trackRef.current;
      if (!viewport || !track) return;
      setDistance(Math.max(0, Math.ceil(track.scrollWidth - viewport.clientWidth)));
    };

    update();
    if (typeof ResizeObserver === 'undefined') {
      window.addEventListener('resize', update);
      return () => window.removeEventListener('resize', update);
    }

    const observer = new ResizeObserver(update);
    if (viewportRef.current) observer.observe(viewportRef.current);
    if (trackRef.current) observer.observe(trackRef.current);
    return () => observer.disconnect();
  }, [text]);

  const overflowing = distance > 1;
  const style: MarqueeStyle = overflowing ? {
    '--settings-marquee-distance': `-${distance}px`,
    '--settings-marquee-duration': `${Math.min(9000, Math.max(2600, 1800 + distance * 22))}ms`,
  } : {};

  return (
    <span
      ref={viewportRef}
      className={`settings-overflow-marquee${overflowing ? ' is-overflowing' : ''}`}
      title={text}
      style={style}
    >
      <span ref={trackRef} className="settings-overflow-marquee-track">{text}</span>
    </span>
  );
}
