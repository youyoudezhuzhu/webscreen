package webservice

import (
	"io/fs"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/gin-gonic/gin"

	"webscreen/utils"
	"webscreen/webservice/android"
)

type WebMasterConfig struct {
	EnableAndroidDiscover bool
}

type WebMaster struct {
	// WSConns []*websocket.Conn

	WebRTCManager *WebRTCManager
	// ScreenSessions map[string]ScreenSession

	pin                  string
	UnlockAttemptRecords map[string]UnlockAttemptRecord
	jwtSecret            []byte

	config              WebMasterConfig
	router              *gin.Engine
	devicesConnected    map[string]Device
	devicesDiscovered   map[string]Device
	devicesDiscoveredMu sync.RWMutex
	pauseDiscovery      bool
	staticFS            fs.FS
}

func New(config WebMasterConfig, staticFS fs.FS) *WebMaster {
	wm := &WebMaster{
		// ScreenSessions:       make(map[string]ScreenSession),
		config:               config,
		devicesDiscovered:    make(map[string]Device),
		staticFS:             staticFS,
		UnlockAttemptRecords: make(map[string]UnlockAttemptRecord),
		WebRTCManager:        NewWebRTCManager(),
	}
	wm.jwtSecret = []byte(time.Now().String())
	return wm
}

func Default(staticFS fs.FS) *WebMaster {
	wm := New(WebMasterConfig{
		EnableAndroidDiscover: true,
	}, staticFS)
	return wm
}

func (wm *WebMaster) setRouter() {
	// gin.SetMode(gin.ReleaseMode)
	r := gin.Default()
	subFS, _ := fs.Sub(wm.staticFS, "static")
	r.StaticFS("/static", http.FS(subFS))

	r.GET("/unlock", func(ctx *gin.Context) {
		ctx.FileFromFS("unlock.html", http.FS(wm.staticFS))
	})
	r.POST("/api/unlock", wm.handleUnlock)

	r.GET("/", func(ctx *gin.Context) {
		ctx.Redirect(302, "/console")
	})
	if wm.pin != "" {
		log.Println("Enable PIN middleware")
		r.Use(wm.HybridAuthMiddleware())
	}
	screen := r.Group("/screen")
	{
		screen.GET("/:id", func(ctx *gin.Context) {
			ctx.FileFromFS("screen.html", http.FS(wm.staticFS))
		})
		screen.GET("/ws", wm.handleScreenWS)
	}

	r.GET("/console", func(c *gin.Context) {
		c.FileFromFS("console.html", http.FS(wm.staticFS))
	})
	api := r.Group("/api")
	{
		api.GET("/device/list", wm.handleListDevices)
		api.POST("/device/connect", wm.handleConnectDevice)
		api.POST("/device/pair", wm.handlePairDevice)
		api.GET("/device/configDescription", wm.handleDeviceConfigDescription)
		// api.GET("/generalConfigDescription", wm.handleGeneralConfigDescription)

		// api.POST("/device/discovery", wm.handleListDevicesDiscoveried)
		// api.POST("/setPIN", wm.handleSetPIN)

		// 统一设备管理（USB / Wi-Fi）：设备列表、事件日志、命名与连接控制
		api.GET("/adb/devices", wm.handleADBDevices)
		api.GET("/adb/events", wm.handleADBEvents)
		api.POST("/adb/usb/rescan", wm.handleADBRescanUSB)
		api.POST("/adb/device/name", wm.handleADBSetName)
		api.POST("/adb/device/wifi", wm.handleADBAddWiFi)
		api.POST("/adb/device/remove", wm.handleADBRemove)
		api.POST("/adb/device/disconnect", wm.handleADBDisconnect)
		api.POST("/adb/device/reconnect", wm.handleADBReconnect)
	}

	wm.router = r
}

func (wm *WebMaster) SetPIN(pin string) {
	// Set the PIN for web access
	// This is a placeholder implementation
	log.Printf("PIN set to: %s", pin)
	wm.pin = pin
}

func (wm *WebMaster) Serve(host, port string) {
	// if wm.config.EnableAndroidDiscover {
	// 	go wm.AndroidDevicesDiscovery()
	// }
	// 统一设备管理器：USB 热插拔、RSA 授权状态与无线重连都由它跟踪。
	// 本机 root 模式下没有 adb，不需要它。
	if !utils.IsLocalRootMode() {
		android.GetManager().Start()
	}
	wm.setRouter()
	err := wm.router.Run(host + ":" + port)
	if err != nil {
		log.Fatalf("Failed to start server: %v", err)
	}
}

func (wm *WebMaster) Close() {
	// for k, v := range maps.All(wm.ScreenSessions) {
	// 	log.Printf("closing session %v", k)
	// 	v.Close()
	// }
}
