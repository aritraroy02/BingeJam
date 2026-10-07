// Shared by index.html and join.html: download button, help after tapping it, and a warning for
// in-app browsers (Instagram, Facebook, Snapchat...) that can't install apps.
(function () {
  const APK = 'https://github.com/aritraroy02/sync-it-releases/releases/latest/download/sync-it.apk';
  const ua = navigator.userAgent || '';
  const isAndroid = /Android/i.test(ua);
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
    APK: APK,

    /** Wires a download button. beforeDownload() may return a promise (e.g. a clipboard copy). */
    setup: function (button, beforeDownload) {
      button.href = APK;
      if (!isAndroid) {
        show('not-android');
        return;
      }
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
        location.href = APK;
      });
      const again = document.getElementById('download-again');
      if (again) again.href = APK;
    },
  };
})();
