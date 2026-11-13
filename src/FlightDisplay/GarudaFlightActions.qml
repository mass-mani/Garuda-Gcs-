import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QGroundControl
import QGroundControl.Controls
import QGroundControl.Controllers

ScrollView {
    id: root
    property var vehicle: QGroundControl.multiVehicleManager.activeVehicle
    readonly property bool live: vehicle !== null && !vehicle.vehicleLinkManager.communicationLost
    clip: true
    contentWidth: availableWidth

    GarudaActionsController {
        id: controller
        vehicle: root.vehicle
    }

    ColumnLayout {
        width: root.availableWidth
        spacing: 8

        QGCLabel {
            Layout.fillWidth: true
            wrapMode: Text.WordWrap
            text: root.live
                  ? qsTr("Vehicle %1 • Servo outputs 7–14").arg(root.vehicle.id)
                  : qsTr("Connect a vehicle to enable servo outputs.")
        }

        QGCLabel {
            Layout.fillWidth: true
            wrapMode: Text.WordWrap
            text: qsTr("LOW sends 1000 µs. HIGH sends 2000 µs.")
        }

        GridLayout {
            Layout.fillWidth: true
            columns: root.availableWidth > 360 ? 2 : 1
            columnSpacing: 6
            rowSpacing: 6

            Repeater {
                model: 8

                delegate: Rectangle {
                    required property int index
                    Layout.fillWidth: true
                    implicitHeight: servoRow.implicitHeight + 12
                    color: qgcPal.window
                    border.color: qgcPal.text
                    border.width: 1
                    radius: 4

                    RowLayout {
                        id: servoRow
                        anchors.fill: parent
                        anchors.margins: 6

                        QGCLabel {
                            Layout.fillWidth: true
                            text: qsTr("Servo %1").arg(index + 7)
                            font.bold: true
                        }

                        QGCButton {
                            text: qsTr("LOW")
                            enabled: root.live
                            onClicked: controller.setServoOutput(index + 7, 1000)
                        }

                        QGCButton {
                            text: qsTr("HIGH")
                            enabled: root.live
                            onClicked: controller.setServoOutput(index + 7, 2000)
                        }
                    }
                }
            }
        }
    }
}
