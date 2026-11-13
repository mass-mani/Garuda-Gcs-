import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtMultimedia
import QGroundControl
import QGroundControl.Controls
import QGroundControl.FactControls
import QGroundControl.FlightDisplay
import QGroundControl.ScreenTools

Rectangle {
    id: root
    required property var videoSource
    property bool expanded: false
    property bool minimized: false
    readonly property bool collapsed: minimized && !wallMode
    property bool wallMode: false
    property int wallColumns: 1
    property int wallRows: 1
    property int wallIndex: 0
    property int wallCapacity: 1
    property real wallHeaderHeight: 0
    property real dockX: 0
    property real dockWidth: 320
    property real margin: 8
    readonly property var settings: videoSource.settings
    x: wallMode ? (wallIndex % wallColumns) * parent.width / wallColumns : (expanded ? margin : dockX)
    y: wallMode ? wallHeaderHeight + Math.floor(wallIndex / wallColumns) * (parent.height - wallHeaderHeight) / wallRows
                : (expanded ? margin : parent.height - height - margin)
    width: wallMode ? parent.width / wallColumns : (expanded ? parent.width - 2 * margin : dockWidth)
    readonly property real paneHeight: wallMode ? (parent.height - wallHeaderHeight) / wallRows
                                              : (expanded ? parent.height - 2 * margin : dockWidth * 9 / 16 + header.height + footer.height)
    height: collapsed ? header.height + 8 : paneHeight
    visible: (wallMode ? wallIndex < wallCapacity : (videoSource.number <= 3 && videoSource.configured)) && !QGroundControl.videoManager.fullScreen
    z: wallMode ? QGroundControl.zOrderWidgets + 1 : (expanded ? QGroundControl.zOrderTopMost : QGroundControl.zOrderWidgets + 1)
    color: "#071521"
    border.color: "#607786"
    radius: 4
    clip: true

    RowLayout {
        id: header
        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.margins: 4
        height: ScreenTools.defaultFontPixelHeight * 1.8
        QGCLabel {
            Layout.fillWidth: true
            text: qsTr("Camera %1").arg(videoSource.number - 1) + (videoSource.recording ? qsTr(" • REC") : "")
            color: videoSource.recording ? "#ff6363" : "white"
            font.bold: true
        }
        QGCButton {
            visible: root.wallMode
            text: qsTr("Setup")
            onClicked: setupPopup.open()
        }
        QGCButton {
            visible: !root.wallMode
            text: root.collapsed ? qsTr("Restore") : qsTr("Minimize")
            onClicked: {
                root.expanded = false
                root.minimized = !root.minimized
            }
        }
        QGCButton {
            visible: !root.collapsed
            text: root.wallMode ? (root.expanded ? qsTr("Restore") : qsTr("Full"))
                                : (root.expanded ? qsTr("Restore") : qsTr("Expand"))
            onClicked: root.expanded = !root.expanded
        }
    }

    Shortcut {
        sequence: "Escape"
        enabled: root.wallMode && root.expanded
        onActivated: root.expanded = false
    }

    Popup {
        id: setupPopup
        parent: root
        x: Math.max(4, Math.min(8, root.width - width - 8))
        y: header.height
        width: Math.max(220, Math.min(root.width - 16, 390))
        padding: 10
        modal: false
        background: Rectangle { color: "#14212c"; border.color: "#4386b8"; radius: 4 }
        ColumnLayout {
            width: parent.width
            LabelledFactComboBox {
                Layout.fillWidth: true
                label: qsTr("Source")
                fact: root.settings.videoSource
                indexModel: false
            }
            LabelledFactTextField {
                Layout.fillWidth: true
                label: qsTr("RTSP URL")
                fact: root.settings.rtspUrl
                visible: root.settings.videoSource.rawValue === root.settings.rtspVideoSource
            }
            LabelledFactComboBox {
                Layout.fillWidth: true
                label: qsTr("RTSP Transport")
                fact: root.settings.rtspTransport
                visible: root.settings.videoSource.rawValue === root.settings.rtspVideoSource
            }
            LabelledFactTextField {
                Layout.fillWidth: true
                label: qsTr("RTSP Jitter Buffer (ms)")
                fact: root.settings.rtspLatencyMs
                visible: root.settings.videoSource.rawValue === root.settings.rtspVideoSource
            }
            FactCheckBoxSlider {
                Layout.fillWidth: true
                text: qsTr("Enable stream")
                fact: root.settings.streamEnabled
                visible: root.settings.videoSource.rawValue !== root.settings.disabledVideoSource
            }
            QGCButton { Layout.fillWidth: true; text: qsTr("Done"); onClicked: setupPopup.close() }
        }
    }
    Rectangle {
        id: viewport
        visible: !root.collapsed
        anchors.top: header.bottom
        height: Math.max(1, root.paneHeight - header.height - footer.height - 16)
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.margins: 4
        color: "black"
        clip: true
        property real ratio: Math.max(0.1, Number(root.settings.aspectRatio.rawValue))
        property int fit: root.settings.videoFit.rawValue
        property bool fitHeight: fit === 1 || (fit === 2 && width / height < ratio) || (fit === 3 && width / height > ratio)
        FlightDisplayViewGStreamer {
            id: streamSurface
            objectName: "videoContent" + videoSource.number
            anchors.centerIn: parent
            width: viewport.fitHeight ? viewport.height * viewport.ratio : viewport.width
            height: viewport.fitHeight ? viewport.height : viewport.width / viewport.ratio
            visible: !videoSource.isUvc && videoSource.decoding
        }
        MediaDevices { id: devices }
        CaptureSession {
            camera: Camera {
                id: usbCamera
                cameraDevice: {
                    const name = String(root.settings.videoSource.rawValue)
                    for (let device of devices.videoInputs) {
                        if (device.description === name) return device
                    }
                    return devices.defaultVideoInput
                }
                active: videoSource.isUvc && videoSource.configured
            }
            videoOutput: usbOutput
        }
        VideoOutput {
            id: usbOutput
            anchors.fill: parent
            visible: videoSource.isUvc
            fillMode: root.settings.videoFit.rawValue === 2 ? VideoOutput.PreserveAspectCrop : VideoOutput.PreserveAspectFit
        }
        QGCLabel {
            anchors.centerIn: parent
            visible: !videoSource.decoding && !videoSource.isUvc
            text: videoSource.status || qsTr("Waiting for video")
            color: "white"
        }
        Repeater {
            model: root.settings.gridLines.rawValue ? 2 : 0
            Rectangle {
                required property int index
                x: (index + 1) * viewport.width / 3
                width: 1; height: viewport.height
                color: "#88ffffff"
            }
        }
        Repeater {
            model: root.settings.gridLines.rawValue ? 2 : 0
            Rectangle {
                required property int index
                y: (index + 1) * viewport.height / 3
                height: 1; width: viewport.width
                color: "#88ffffff"
            }
        }
    }
    RowLayout {
        id: footer
        visible: !root.collapsed
        anchors.bottom: parent.bottom
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.margins: 4
        height: ScreenTools.defaultFontPixelHeight * 2
        spacing: 4
        QGCButton {
            Layout.fillWidth: true
            text: videoSource.recording ? qsTr("Stop") : qsTr("Record")
            enabled: videoSource.decoding
            visible: !videoSource.isUvc
            onClicked: videoSource.toggleRecording()
        }
        QGCButton {
            Layout.fillWidth: true
            text: qsTr("Snapshot")
            enabled: videoSource.decoding || (videoSource.isUvc && usbCamera.active)
            onClicked: {
                const path = videoSource.snapshotPath()
                if (!path) { mainWindow.showMessageDialog(qsTr("Snapshot"), qsTr("Set a writable photo save folder in Settings.")); return }
                const surface = videoSource.isUvc ? usbOutput : streamSurface
                surface.grabToImage(function(result) {
                    if (!result.saveToFile(path)) mainWindow.showMessageDialog(qsTr("Snapshot"), qsTr("Could not save snapshot."))
                })
            }
        }
        QGCButton {
            Layout.fillWidth: true
            text: qsTr("Retry")
            visible: !videoSource.isUvc
            onClicked: videoSource.reconnect()
        }
    }
}
