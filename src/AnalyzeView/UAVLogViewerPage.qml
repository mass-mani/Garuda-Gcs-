import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Window
import QtWebEngine
import Garuda.Analyze
import QGroundControl.Controls
import QGroundControl.Palette

Rectangle {
    id: root
    color: palette.window
    signal popout()
    property string loadError: ""
    property string downloadStatus: ""

    function openViewerWindow(request) {
        // Parent each window to this page so leaving Analyze Tools also closes it.
        var popup = viewerPopup.createObject(root)
        if (!popup) {
            console.warn("UAV Log Viewer: unable to create a plot window")
            return
        }
        request.openIn(popup.webView)
        popup.show()
    }

    QGCPalette { id: palette }
    UAVLogViewerServer { id: server; Component.onCompleted: start() }
    WebEngineProfile {
        id: viewerProfile
        offTheRecord: true
        onDownloadRequested: function(download) {
            root.downloadStatus = qsTr("Saving to %1/%2").arg(download.downloadDirectory).arg(download.downloadFileName)
            download.accept()
        }
    }

    WebEngineView {
        id: viewer
        objectName: "garudaUavLogViewer"
        anchors.fill: parent
        anchors.bottomMargin: status.visible ? status.height : 0
        url: server.url
        profile: viewerProfile
        backgroundColor: palette.window
        settings.javascriptCanOpenWindows: true
        settings.fullScreenSupportEnabled: true
        settings.screenCaptureEnabled: true
        settings.webGLEnabled: true
        onLoadingChanged: function(info) {
            if (info.status === WebEngineView.LoadFailedStatus) root.loadError = info.errorString
            else if (info.status === WebEngineView.LoadSucceededStatus) root.loadError = ""
        }
        onJavaScriptConsoleMessage: function(level, message, line, source) {
            if (level === WebEngineView.ErrorMessageLevel) console.warn("UAV Log Viewer: " + message)
        }
        onRenderProcessTerminated: function(status, code) {
            root.loadError = qsTr("The log viewer stopped (code %1). Reopen Analyze Tools to restart it.").arg(code)
        }
        onNewWindowRequested: function(request) {
            root.openViewerWindow(request)
        }
        onDesktopMediaRequested: function(request) {
            captureDialog.request = request
            captureDialog.open()
        }
        onFullScreenRequested: function(request) { request.accept() }
    }

    QGCLabel {
        id: status
        anchors.bottom: parent.bottom
        width: parent.width
        text: root.downloadStatus
        visible: text.length > 0
        wrapMode: Text.Wrap
    }
    Rectangle {
        anchors.fill: parent
        color: palette.window
        visible: server.error.length > 0 || root.loadError.length > 0
        ColumnLayout {
            anchors.centerIn: parent
            width: parent.width * 0.8
            QGCLabel {
                Layout.fillWidth: true
                wrapMode: Text.Wrap
                text: server.error || root.loadError
            }
            QGCButton {
                text: qsTr("Retry")
                onClicked: { root.loadError = ""; server.start(); viewer.reload() }
            }
        }
    }
    ProgressBar {
        anchors.top: parent.top
        width: parent.width
        from: 0; to: 100; value: viewer.loadProgress
        visible: viewer.loading
    }
    Component {
        id: viewerPopup
        Window {
            id: popupWindow
            property alias webView: popupView
            width: 1100; height: 750
            title: qsTr("Garuda Aerospace — UAV Log Viewer")
            onClosing: popupWindow.destroy()
            WebEngineView {
                id: popupView
                anchors.fill: parent
                profile: viewerProfile
                backgroundColor: palette.window
                settings.javascriptCanOpenWindows: true
                onNewWindowRequested: function(request) {
                    root.openViewerWindow(request)
                }
                onWindowCloseRequested: popupWindow.close()
            }
        }
    }
    Dialog {
        id: captureDialog
        property var request: null
        title: qsTr("Choose a screen or window to record")
        anchors.centerIn: parent
        width: Math.min(root.width * 0.8, 650)
        height: Math.min(root.height * 0.8, 500)
        modal: true
        standardButtons: Dialog.Cancel
        onRejected: { if (request) request.cancel(); request = null }
        contentItem: ScrollView {
            Column {
                width: parent.width
                Repeater {
                    model: captureDialog.request ? captureDialog.request.screensModel : null
                    QGCButton {
                        required property int index
                        width: parent.width
                        text: model.display
                        onClicked: {
                            captureDialog.request.selectScreen(captureDialog.request.screensModel.index(index, 0))
                            captureDialog.request = null
                            captureDialog.close()
                        }
                    }
                }
                Repeater {
                    model: captureDialog.request ? captureDialog.request.windowsModel : null
                    QGCButton {
                        required property int index
                        width: parent.width
                        text: model.display
                        onClicked: {
                            captureDialog.request.selectWindow(captureDialog.request.windowsModel.index(index, 0))
                            captureDialog.request = null
                            captureDialog.close()
                        }
                    }
                }
            }
        }
    }
}
