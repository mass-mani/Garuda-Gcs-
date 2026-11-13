package rtspserver

import (
	"fmt"
	"net"
	"strings"
	"sync"

	"github.com/bluenviron/gortsplib/v5"
	"github.com/bluenviron/gortsplib/v5/pkg/base"
	"github.com/bluenviron/gortsplib/v5/pkg/description"
	"github.com/bluenviron/gortsplib/v5/pkg/format"
	"github.com/pion/rtp"
)

var active struct {
	sync.Mutex
	server  *gortsplib.Server
	handler *relayHandler
	path    string
}

// Start opens a local RTSP listener. The single publisher must connect from
// loopback; LAN clients can read the stream without authenticating.
func Start(port int, streamPath string) error {
	if port < 1 || port > 65535 {
		return fmt.Errorf("invalid RTSP port")
	}
	streamPath = "/" + strings.Trim(streamPath, "/")
	if !strings.HasPrefix(streamPath, "/live/") || strings.Contains(streamPath, "..") {
		return fmt.Errorf("invalid local stream path")
	}

	active.Lock()
	defer active.Unlock()
	if active.server != nil {
		if active.path == streamPath {
			return nil
		}
		return fmt.Errorf("local RTSP server is already running")
	}

	h := &relayHandler{expectedPath: streamPath}
	s := &gortsplib.Server{
		Handler:        h,
		RTSPAddress:    fmt.Sprintf("0.0.0.0:%d", port),
		UDPRTPAddress:  ":8000",
		UDPRTCPAddress: ":8001",
	}
	h.server = s
	if err := s.Start(); err != nil {
		return fmt.Errorf("cannot listen on RTSP port %d: %w", port, err)
	}
	active.server = s
	active.handler = h
	active.path = streamPath
	go func() {
		_ = s.Wait()
		active.Lock()
		if active.server == s {
			active.server = nil
			active.handler = nil
			active.path = ""
		}
		active.Unlock()
	}()
	return nil
}

// Stop closes the listener and all publisher/viewer sessions.
func Stop() {
	active.Lock()
	s := active.server
	h := active.handler
	active.server = nil
	active.handler = nil
	active.path = ""
	active.Unlock()
	if h != nil {
		h.closeStream()
	}
	if s != nil {
		s.Close()
	}
}

type relayHandler struct {
	server       *gortsplib.Server
	expectedPath string
	mutex        sync.RWMutex
	stream       *gortsplib.ServerStream
	publisher    *gortsplib.ServerSession
}

func (h *relayHandler) matches(path string) bool {
	return "/"+strings.Trim(path, "/") == h.expectedPath
}

func (h *relayHandler) OnAnnounce(ctx *gortsplib.ServerHandlerOnAnnounceCtx) (*base.Response, error) {
	if !h.matches(ctx.Path) || !isLoopback(ctx.Conn.NetConn().RemoteAddr()) {
		return &base.Response{StatusCode: base.StatusUnauthorized}, nil
	}

	h.mutex.Lock()
	defer h.mutex.Unlock()
	if h.stream != nil {
		h.stream.Close()
		if h.publisher != nil && h.publisher != ctx.Session {
			h.publisher.Close()
		}
	}
	stream := &gortsplib.ServerStream{Server: h.server, Desc: ctx.Description}
	if err := stream.Initialize(); err != nil {
		return nil, err
	}
	h.stream = stream
	h.publisher = ctx.Session
	return &base.Response{StatusCode: base.StatusOK}, nil
}

func (h *relayHandler) OnDescribe(ctx *gortsplib.ServerHandlerOnDescribeCtx) (*base.Response, *gortsplib.ServerStream, error) {
	if !h.matches(ctx.Path) {
		return &base.Response{StatusCode: base.StatusNotFound}, nil, nil
	}
	h.mutex.RLock()
	defer h.mutex.RUnlock()
	if h.stream == nil {
		return &base.Response{StatusCode: base.StatusNotFound}, nil, nil
	}
	return &base.Response{StatusCode: base.StatusOK}, h.stream, nil
}

func (h *relayHandler) OnSetup(ctx *gortsplib.ServerHandlerOnSetupCtx) (*base.Response, *gortsplib.ServerStream, error) {
	if !h.matches(ctx.Path) {
		return &base.Response{StatusCode: base.StatusNotFound}, nil, nil
	}
	if ctx.Session.State() == gortsplib.ServerSessionStatePreRecord {
		if !isLoopback(ctx.Conn.NetConn().RemoteAddr()) {
			return &base.Response{StatusCode: base.StatusUnauthorized}, nil, nil
		}
		return &base.Response{StatusCode: base.StatusOK}, nil, nil
	}
	h.mutex.RLock()
	defer h.mutex.RUnlock()
	if h.stream == nil {
		return &base.Response{StatusCode: base.StatusNotFound}, nil, nil
	}
	return &base.Response{StatusCode: base.StatusOK}, h.stream, nil
}

func (h *relayHandler) OnRecord(ctx *gortsplib.ServerHandlerOnRecordCtx) (*base.Response, error) {
	h.mutex.RLock()
	stream := h.stream
	valid := stream != nil && ctx.Session == h.publisher && h.matches(ctx.Path)
	h.mutex.RUnlock()
	if !valid {
		return &base.Response{StatusCode: base.StatusUnauthorized}, nil
	}
	ctx.Session.OnPacketRTPAny(func(media *description.Media, forma format.Format, packet *rtp.Packet) {
		if err := stream.WritePacketRTP(media, packet); err != nil {
			// A reader may disconnect while a packet is being fanned out.
			return
		}
	})
	return &base.Response{StatusCode: base.StatusOK}, nil
}

func (h *relayHandler) OnPlay(_ *gortsplib.ServerHandlerOnPlayCtx) (*base.Response, error) {
	return &base.Response{StatusCode: base.StatusOK}, nil
}

func (h *relayHandler) OnSessionClose(ctx *gortsplib.ServerHandlerOnSessionCloseCtx) {
	h.mutex.Lock()
	defer h.mutex.Unlock()
	if h.stream != nil && ctx.Session == h.publisher {
		h.stream.Close()
		h.stream = nil
		h.publisher = nil
	}
}

func (h *relayHandler) closeStream() {
	h.mutex.Lock()
	defer h.mutex.Unlock()
	if h.stream != nil {
		h.stream.Close()
		h.stream = nil
		h.publisher = nil
	}
}

func isLoopback(addr net.Addr) bool {
	tcp, ok := addr.(*net.TCPAddr)
	return ok && tcp.IP.IsLoopback()
}
