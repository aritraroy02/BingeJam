// Product name in one place (mirrors core/.../config/Brand.kt). The name may change again.
export const BRAND = {
  name: 'BingeJam',
  tagline: 'Your world. In sync.'
} as const

// Links shared with the Android app and the website (core/.../config/RoomLinks.kt, LegalLinks.kt).
export const LINKS = {
  webHost: 'syncit-watchparty.web.app',
  joinLink: (code: string) => `https://syncit-watchparty.web.app/join/${code}`,
  terms: 'https://syncit-watchparty.web.app/terms',
  privacy: 'https://syncit-watchparty.web.app/privacy',
  deleteAccount: 'https://syncit-watchparty.web.app/delete-account'
} as const
