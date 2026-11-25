import type { JSX } from 'react'
import { Icon } from './Icon'
import { SyncDots } from './primitives'

// "In a room" at the bottom of the sidebar while this PC is in a room and the room screen isn't open
// (2026-11-25, user: "once we came out of room there is no way we can go back and stop the room"):
// which room, what's playing, Back to it, and Leave (the last one out ends it).

export interface RoomDockVM {
  /** "Somesh Barik", "Movie Night" */
  name: string
  /** "Midnight Signal · S2:E4" or "Nothing playing yet" */
  title: string
  /** Nobody else is in: leaving ends the room. */
  alone: boolean
}

export function RoomDock({
  room,
  onOpen,
  onLeave,
  compact = false
}: {
  room: RoomDockVM
  onOpen: () => void
  onLeave: () => void
  /** The icon rail: two icon buttons. */
  compact?: boolean
}): JSX.Element {
  const leaveLabel = room.alone ? 'End room' : 'Leave room'
  if (compact) {
    return (
      <div className="voice-dock voice-dock--rail" role="region" aria-label={`In a room: ${room.name}`}>
        <button type="button" className="dock-btn pressable" title={`Back to the room\n${room.name} · ${room.title}`} aria-label={`Back to the room with ${room.name}`} onClick={onOpen}>
          <Icon name="startRoom" size={16} />
        </button>
        <button type="button" className="dock-btn dock-btn--leave pressable" title={leaveLabel} aria-label={leaveLabel} onClick={onLeave}>
          <Icon name="logout" size={16} />
        </button>
      </div>
    )
  }
  return (
    <div className="voice-dock" role="region" aria-label="Room">
      <span className="voice-dock__status">
        <SyncDots />
        <span className="voice-dock__label voice-dock__label--on">In a room</span>
      </span>
      <button type="button" className="voice-dock__where truncate" title={`Back to the room with ${room.name}`} onClick={onOpen}>
        {room.name}
      </button>
      <span className="voice-dock__server truncate" title={room.title}>
        {room.title}
      </span>
      <div className="voice-dock__controls">
        <button type="button" className="dock-btn dock-btn--text pressable" onClick={onOpen}>
          Back to room
        </button>
        <button type="button" className="dock-btn dock-btn--leave dock-btn--text pressable" title={room.alone ? 'You are the last one in: leaving ends the room' : undefined} onClick={onLeave}>
          {room.alone ? 'End' : 'Leave'}
        </button>
      </div>
    </div>
  )
}
