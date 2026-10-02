import { useLayoutEffect, useRef, useState, type CSSProperties } from 'react';
import './overflow-marquee.css';

type MarqueeStyle = CSSProperties & {
  '--marquee-distance'?: string;
  '--marquee-duration'?: string;
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
      // Integer scroll widths can miss text clipped at a fractional pixel boundary.
      const range = document.createRange();
      range.selectNodeContents(track);
      setDistance(Math.max(0, Math.ceil(
        range.getBoundingClientRect().width - viewport.getBoundingClientRect().width,
      )));
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

  const overflowing = distance > 0;
  const style: MarqueeStyle = overflowing ? {
    '--marquee-distance': `-${distance}px`,
    '--marquee-duration': `${Math.min(9000, Math.max(2600, 1800 + distance * 22))}ms`,
  } : {};

  return (
    <span
      ref={viewportRef}
      className={`overflow-marquee${overflowing ? ' is-overflowing' : ''}`}
      title={text}
      style={style}
    >
      <span ref={trackRef} className="overflow-marquee-track">{text}</span>
    </span>
  );
}
