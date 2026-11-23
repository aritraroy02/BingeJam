// Shared by index.html, join.html and add.html: the "Get BingeJam" button (opens BingeJam on the Indus
// Appstore, the only place it's distributed and updated from), help after tapping it, a warning for
// in-app browsers (Instagram, Facebook, Snapchat...) that can't open app stores, and the Windows app.
// One main and one second action per device: Android gets the store badge (#phone), a computer gets
// the PC app and a QR code for the phone (#not-android).
(function () {
  const STORE = 'https://indusapp.store/qcsredto';
  // The Windows app: an installer attached to a GitHub release of the public releases repo, tagged
  // pc-vX.Y.Z so the phones' update check (v-tags only) never mistakes it for a phone release.
  const PC_VERSION = '0.1.3';
  const PC_URL = 'https://github.com/aritraroy02/sync-it-releases/releases/download/pc-v' + PC_VERSION + '/BingeJam-Setup-' + PC_VERSION + '.exe';
  const ua = navigator.userAgent || '';
  const isAndroid = /Android/i.test(ua);
  const isWindows = /Windows NT/i.test(ua);
  // Any phone or tablet, including iPads that report as a Mac.
  const isPhone = isAndroid || /iPhone|iPad|iPod|Mobile/i.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1);
  // Chrome Custom Tabs (WhatsApp, Telegram, Gmail) report as Chrome and work fine. Full in-app
  // WebViews mark themselves with "; wv)" or their app name, and drop APK downloads.
  const inAppBrowser = /; wv\)|FBAN|FBAV|FB_IAB|Instagram|Snapchat|Line\/|MicroMessenger|LinkedInApp/i.test(ua);

  function chromeIntent() {
    const url = location.href.replace(/^https?:\/\//, '');
    return 'intent://' + url + '#Intent;scheme=https;package=com.android.chrome;end';
  }

  function show(id) {
    const el = document.getElementById(id);
    if (el) el.hidden = false;
  }

  window.SyncItDownload = {
    STORE: STORE,
    PC_URL: PC_URL,
    PC_VERSION: PC_VERSION,

    /**
     * Wires a "Download for Windows" link inside its (hidden) .pc-get box: phones and tablets never
     * see it; on a computer it shows with the version under it, first in the row on Windows, and once
     * clicked shows #after-pc-download (the SmartScreen steps).
     */
    setupPc: function (link) {
      if (!link) return;
      link.href = PC_URL;
      const box = link.closest('.pc-get');
      if (isPhone || !box) return;
      const meta = box.querySelector('.pc-meta');
      if (meta) {
        meta.textContent = 'Version ' + PC_VERSION + ' · ' + (isWindows ? 'Windows 10 and 11 · Free' : 'Windows 10 and 11 only for now');
      }
      box.hidden = false;
      if (isWindows) {
        const row = box.closest('.get');
        if (row) row.classList.add('pc-first');
      }
      link.addEventListener('click', function () {
        // No preventDefault: the download starts as usual; the steps appear under it.
        show('after-pc-download');
      });
    },

    /** Wires a download button. beforeDownload() may return a promise (e.g. a clipboard copy). */
    setup: function (button, beforeDownload) {
      button.href = STORE;
      if (!isAndroid) {
        // A computer can't install from the store page: its visitors get the QR code instead.
        button.hidden = true;
        show('not-android');
        return;
      }
      show('phone');
      if (inAppBrowser) {
        show('in-app');
        const open = document.getElementById('open-chrome');
        if (open) open.href = chromeIntent();
      }
      button.addEventListener('click', async function (event) {
        event.preventDefault();
        try { if (beforeDownload) await beforeDownload(); } catch (e) { /* download anyway */ }
        show('after-download');
        const help = document.getElementById('after-download');
        if (help) help.scrollIntoView({ behavior: 'smooth', block: 'start' });
        location.href = STORE;
      });
      const again = document.getElementById('download-again');
      if (again) again.href = STORE;
    },
  };
})();
