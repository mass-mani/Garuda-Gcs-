import QtQuick
import QGroundControl
import QGroundControl.Controls
import QGroundControl.AppSettings

Item {
    id: root
    required property var guidedController
    readonly property real occupiedWidth: width

    Flickable {
        anchors.fill: parent
        contentWidth: width
        contentHeight: panels.implicitHeight
        clip: true
        boundsBehavior: Flickable.StopAtBounds

        Column {
            id: panels
            width: parent.width
            spacing: 6

            GarudaRetractablePanel {
                width: parent.width
                title: qsTr("Servo Outputs 7–14")
                settingsKey: "GarudaServoPanelExpanded"
                expandedHeight: Math.max(headerHeight + 300, root.height * 0.62)
                panelContent: Component { GarudaFlightActions { } }
            }

            GarudaRetractablePanel {
                width: parent.width
                title: qsTr("Video / RTSP")
                settingsKey: "GarudaVideoPanelExpanded"
                expandedHeight: Math.max(headerHeight + 240, root.height * 0.52)
                panelContent: Component {
                    VideoSourceSettings {
                        sourceSettings: QGroundControl.settingsManager.videoSettings
                        sourceNumber: 1
                    }
                }
            }
        }
    }
}
