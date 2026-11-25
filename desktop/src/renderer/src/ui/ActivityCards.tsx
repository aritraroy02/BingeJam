import { useEffect, useState, type JSX } from 'react'
import { playingFor } from '@shared/games'
import { clock, positionNow, type NowPlaying } from '@shared/music'

// Your profile card's activity, like Discord's (2026-11-25): the game you're playing and the song in
// Spotify with its album art and a running progress bar. The art and position stay on this PC.

export interface GameNow {
  name: string
  /** Date.now() when the app spotted it (display only). */
  since: number
}

/** Re-renders every second while [on]: the progress bar and "for 12 min". */
function useTick(on: boolean): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!on) return
    const t = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(t)
  }, [on])
  return now
}

export function ActivityCards({ game, song }: { game: GameNow | null; song: NowPlaying | null }): JSX.Element | null {
  const now = useTick(game !== null || song?.playing === true)
  if (!game && !song) return null
  return (
    <div className="activity-cards">
      {game ? (
        <section className="activity-card" aria-label={`Playing ${game.name}`}>
          <span className="activity-card__label">Playing</span>
          <div className="activity-card__row">
            <span className="activity-card__tile" aria-hidden="true">
              {game.name.trim().charAt(0).toUpperCase()}
            </span>
            <span className="activity-card__text">
              <span className="activity-card__title truncate">{game.name}</span>
              <span className="activity-card__sub">{playingFor(game.since, now)}</span>
            </span>
          </div>
        </section>
      ) : null}
      {song ? <SongCard song={song} now={now} /> : null}
    </div>
  )
}

function SongCard({ song, now }: { song: NowPlaying; now: number }): JSX.Element {
  const at = positionNow(song, now)
  const share = at !== null && song.durationMs ? Math.min(1, at / song.durationMs) : null
  return (
    <section className="activity-card" aria-label={`Listening to ${song.title} on Spotify`}>
      <span className="activity-card__label">{song.playing ? 'Listening to Spotify' : 'Spotify · paused'}</span>
      <div className="activity-card__row">
        {song.artwork ? (
          <img className="activity-card__art" src={song.artwork} alt="" width={56} height={56} />
        ) : (
          <span className="activity-card__tile activity-card__tile--art" aria-hidden="true">
            {song.title.trim().charAt(0).toUpperCase()}
          </span>
        )}
        <span className="activity-card__text">
          <span className="activity-card__title truncate" title={song.title}>
            {song.title}
          </span>
          {song.artist ? (
            <span className="activity-card__sub truncate" title={song.artist}>
              {song.artist}
            </span>
          ) : null}
          {share !== null && at !== null && song.durationMs ? (
            <span className="activity-card__progress tabular">
              <span>{clock(at)}</span>
              <span className="activity-card__bar" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(share * 100)} aria-label="Song progress">
                <span style={{ transform: `scaleX(${share})` }} />
              </span>
              <span>{clock(song.durationMs)}</span>
            </span>
          ) : null}
        </span>
      </div>
    </section>
  )
}
