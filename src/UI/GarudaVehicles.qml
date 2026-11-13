import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QGroundControl
import QGroundControl.Controllers
import QGroundControl.Controls
import QGroundControl.Palette

Rectangle {
    id: root
    color: palette.window
    QGCPalette { id: palette; colorGroupEnabled: true }
    GarudaVehicleDirectory { id: directory }
    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 20
        spacing: 12
        RowLayout {
            Layout.fillWidth: true
            QGCLabel { text: qsTr("VEHICLES"); font.pointSize: 20; Layout.fillWidth: true }
            QGCButton {
                text: qsTr("Manage connections")
                onClicked: mainWindow.showSettingsTool(qsTr("Comm Links"))
            }
        }
        QGCLabel {
            Layout.fillWidth: true
            wrapMode: Text.WordWrap
            text: qsTr("Supports more than 10 vehicles. Rename each aircraft below and give every aircraft a unique MAVLink System ID. One shared UDP link can carry many aircraft; the app supports up to 31 independent MAVLink links. Disconnecting a shared link affects every aircraft using it.")
        }
        QGCLabel {
            visible: QGroundControl.multiVehicleManager.vehicles.count === 0
            text: qsTr("No vehicles connected. Open Manage connections to add Serial, UDP or TCP links.")
        }
        ListView {
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            spacing: 10
            model: QGroundControl.multiVehicleManager.vehicles
            delegate: Rectangle {
                id: card
                required property var object
                width: ListView.view.width
                height: content.implicitHeight + 24
                color: palette.windowShade
                border.width: 2
                border.color: object === QGroundControl.multiVehicleManager.activeVehicle ? palette.colorGreen : palette.windowShade
                radius: 6
                ColumnLayout {
                    id: content
                    anchors { left: parent.left; right: parent.right; top: parent.top; margins: 12 }
                    RowLayout {
                        Layout.fillWidth: true
                        QGCTextField {
                            id: alias
                            Layout.fillWidth: true
                            maximumLength: 64
                            text: { directory.revision; card.object.vehicleUIDStr; return directory.name(card.object) }
                        }
                        QGCButton {
                            text: qsTr("Save name")
                            onClicked: if (!directory.rename(card.object, alias.text)) mainWindow.showMessageDialog(qsTr("Vehicle name"), qsTr("Enter a name of 1–64 characters. The vehicle must still be connected."))
                        }
                        QGCButton {
                            text: card.object === QGroundControl.multiVehicleManager.activeVehicle ? qsTr("Active vehicle") : qsTr("Select vehicle")
                            enabled: !directory.identityConflict(card.object)
                            onClicked: directory.select(card.object)
                        }
                    }
                    QGCLabel {
                        text: qsTr("SYSID %1 · Component %2 · %3 · %4").arg(card.object.id).arg(directory.componentId(card.object)).arg(card.object.vehicleTypeString).arg(card.object.flightMode)
                    }
                    GridLayout {
                        Layout.fillWidth: true
                        columns: 3
                        rowSpacing: 3
                        columnSpacing: 16
                        QGCLabel { text: qsTr("ALT REL  %1 m").arg(card.object.vehicle.altitudeRelative.valueString) }
                        QGCLabel { text: qsTr("SPEED  %1 m/s").arg(card.object.vehicle.groundSpeed.valueString) }
                        QGCLabel { text: qsTr("HEADING  %1°").arg(card.object.vehicle.heading.valueString) }
                        QGCLabel { text: qsTr("GPS  %1").arg(card.object.gps.lock.enumOrValueString) }
                        QGCLabel { text: qsTr("SATELLITES  %1").arg(card.object.gps.count.valueString) }
                        QGCLabel {
                            text: card.object.coordinate.isValid
                                  ? qsTr("POSITION  %1, %2").arg(card.object.coordinate.latitude.toFixed(6)).arg(card.object.coordinate.longitude.toFixed(6))
                                  : qsTr("POSITION  unavailable")
                        }
                    }
                    QGCLabel {
                        text: { card.object.vehicleLinkManager.primaryLinkName; return directory.connection(card.object) }
                    }
                    QGCLabel {
                        text: (card.object.vehicleLinkManager.communicationLost ? qsTr("CONNECTION LOST") : qsTr("CONNECTED")) + " · " + (card.object.armed ? qsTr("ARMED") : qsTr("DISARMED"))
                        color: card.object.vehicleLinkManager.communicationLost ? palette.colorRed : palette.colorGreen
                    }
                    QGCLabel {
                        visible: directory.identityConflict(card.object)
                        text: qsTr("IDENTITY CONFLICT · another connected SYSID reports the same autopilot UID. Selection is blocked until identity is verified.")
                        color: palette.colorRed
                        wrapMode: Text.WordWrap
                        Layout.fillWidth: true
                    }
                    QGCLabel {
                        visible: !card.object.vehicleUID
                        text: qsTr("Hardware identity unavailable; this preferred name is stored for this System ID.")
                    }
                }
            }
        }
    }
}
