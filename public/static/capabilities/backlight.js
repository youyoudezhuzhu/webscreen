/**
 * 受控端黑屏（隐私保护）
 *
 * 只关背光、不锁屏：设备保持唤醒，触摸/按键/串流照常，屏幕对外是黑的。
 * 与键盘/鼠标一样通过 HTTP API 调服务端执行（服务端再按部署形态
 * 决定走本机 su 还是 adb -s），不依赖 UHID 或 DataChannel。
 */
(function () {
    const btn = document.getElementById('backlightToggleBtn');
    if (!btn) return;

    const TITLE_OFF = '黑屏（隐私保护：屏幕不显示，串流与触控照常）';
    const TITLE_ON = '屏幕已黑（点击恢复显示）';

    let screenOff = false;
    let busy = false;

    // APK 内嵌模式下服务就在手机上，串号留空即可；
    // NAS 托管模式从页面标识取设备串号。
    function deviceSerial() {
        if (window.deviceIdentifier) return String(window.deviceIdentifier);
        const m = location.pathname.match(/^\/screen\/([^/]+)/);
        return m ? decodeURIComponent(m[1]) : '';
    }

    function flashError() {
        btn.classList.add('ctrl-error');
        setTimeout(() => btn.classList.remove('ctrl-error'), 1200);
    }

    async function setBacklight(off) {
        if (busy) return;
        busy = true;
        btn.classList.add('busy');
        try {
            const res = await fetch('/api/screen/backlight', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ serial: deviceSerial(), off: !!off })
            });
            const data = await res.json().catch(() => ({}));
            if (!res.ok || data.result !== 'success') {
                throw new Error(data.message || ('HTTP ' + res.status));
            }
            screenOff = !!off;
            btn.classList.toggle('active', screenOff);
            btn.setAttribute('title', screenOff ? TITLE_ON : TITLE_OFF);
        } catch (e) {
            console.error('[backlight] 切换失败:', e);
            flashError();
        } finally {
            busy = false;
            btn.classList.remove('busy');
        }
    }

    btn.setAttribute('title', TITLE_OFF);
    btn.addEventListener('click', () => setBacklight(!screenOff));

    // 离开页面时恢复背光，避免把手机留在黑屏状态
    window.addEventListener('beforeunload', () => {
        if (!screenOff) return;
        const body = JSON.stringify({ serial: deviceSerial(), off: false });
        try {
            if (navigator.sendBeacon) {
                navigator.sendBeacon('/api/screen/backlight', new Blob([body], { type: 'application/json' }));
            } else {
                fetch('/api/screen/backlight', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body, keepalive: true });
            }
        } catch (e) { /* ignore */ }
    });

    window.setDeviceBacklight = setBacklight;
})();
