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
    GarudaSwarmController { id: swarm }
    GarudaVehicleDirectory { id: directory }

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 20
        spacing: 12

        RowLayout {
            Layout.fillWidth: true
            QGCLabel { text: qsTr("SWARM GROUPS"); font.pointSize: 20; Layout.fillWidth: true }
            QGCTextField { id: groupName; placeholderText: qsTr("New group name"); maximumLength: 48; Layout.preferredWidth: 260 }
            QGCButton {
                text: qsTr("Create group")
                onClicked: {
                    if (!swarm.createGroup(groupName.text))
                        mainWindow.showMessageDialog(qsTr("Create group"), qsTr("Use a unique name of 1–48 characters."))
                    else groupName.clear()
                }
            }
        }
        QGCLabel {
            Layout.fillWidth: true
            wrapMode: Text.WordWrap
            text: qsTr("Groups only manage local vehicle selection. Activating a group sends no flight commands. Members with unavailable hardware UID are tied to this connection session and must be assigned again after restart or reconnect.")
        }
        QGCLabel { text: qsTr("CONNECTED VEHICLES"); font.pointSize: 15 }
        ListView {
            id: vehicleDashboard
            Layout.fillWidth: true
            Layout.preferredHeight: 112
            orientation: ListView.Horizontal
            spacing: 8
            clip: true
            model: QGroundControl.multiVehicleManager.vehicles
            delegate: Rectangle {
                required property var object
                width: 250
                height: vehicleDashboard.height - 4
                radius: 5
                color: palette.windowShade
                border.color: directory.identityConflict(object) ? palette.colorRed : palette.groupBorder
                ColumnLayout {
                    anchors.fill: parent
                    anchors.margins: 8
                    spacing: 2
                    RowLayout {
                        Layout.fillWidth: true
                        QGCLabel { text: directory.name(object); font.bold: true; Layout.fillWidth: true; elide: Text.ElideRight }
                        QGCLabel {
                            text: object.vehicleLinkManager.communicationLost ? qsTr("LOST") : qsTr("CONNECTED")
                            color: object.vehicleLinkManager.communicationLost ? palette.colorRed : palette.colorGreen
                        }
                    }
                    QGCLabel { text: qsTr("SYSID %1 · %2 · %3").arg(object.id).arg(object.flightMode).arg(object.armed ? qsTr("ARMED") : qsTr("DISARMED")); Layout.fillWidth: true; elide: Text.ElideRight }
                    QGCLabel {
                        text: qsTr("GPS %1 · Alt %2 m · Speed %3 m/s").arg(object.gps.telemetryAvailable ? object.gps.lock.valueString : qsTr("—")).arg(object.vehicle.altitudeRelative.valueString).arg(object.vehicle.groundSpeed.valueString)
                        Layout.fillWidth: true
                        elide: Text.ElideRight
                    }
                    QGCLabel {
                        text: object.batteries.count > 0 ? qsTr("Battery %1%").arg(object.batteries.get(0).percentRemaining.valueString) : qsTr("Battery unavailable")
                        Layout.fillWidth: true
                    }
                }
            }
        }
        RowLayout {
            Layout.fillWidth: true
            QGCLabel {
                Layout.fillWidth: true
                text: swarm.status + (swarm.enabled ? qsTr(" · Active: %1").arg(swarm.activeGroup) : "")
                color: swarm.enabled ? palette.colorGreen : palette.text
                wrapMode: Text.WordWrap
            }
            QGCButton { visible: swarm.enabled; text: qsTr("Disable group"); onClicked: swarm.disableGroup() }
        }

        ListView {
            Layout.fillWidth: true
            Layout.preferredHeight: Math.min(contentHeight, 180)
            clip: true
            spacing: 4
            model: swarm.groups
            delegate: Rectangle {
                required property var modelData
                width: ListView.view.width
                height: 52
                color: swarm.selectedGroup === modelData.name ? palette.windowShade : palette.window
                radius: 4
                RowLayout {
                    anchors.fill: parent
                    anchors.margins: 6
                    QGCLabel { text: modelData.name; Layout.fillWidth: true }
                    QGCLabel { text: qsTr("%1 members").arg(modelData.count) }
                    QGCButton { text: qsTr("Edit"); onClicked: swarm.editGroup(modelData.name) }
                    QGCButton { text: qsTr("Activate"); onClicked: swarm.activateGroup(modelData.name) }
                    QGCButton { text: qsTr("Delete"); onClicked: swarm.deleteGroup(modelData.name) }
                }
            }
        }

        QGCLabel {
            visible: swarm.selectedGroup.length > 0
            text: qsTr("MEMBERS · %1").arg(swarm.selectedGroup)
            font.pointSize: 15
        }
        QGCLabel {
            visible: swarm.selectedGroup.length === 0
            text: qsTr("Create or edit a group, then select connected vehicles below.")
        }
        ListView {
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: swarm.selectedGroup.length > 0
            clip: true
            spacing: 2
            model: QGroundControl.multiVehicleManager.vehicles
            delegate: Rectangle {
                required property var object
                width: ListView.view.width
                height: 52
                color: palette.windowShade
                RowLayout {
                    anchors.fill: parent
                    anchors.margins: 8
                    CheckBox {
                        text: directory.name(object) + qsTr(" · SYSID %1").arg(object.id)
                        checked: swarm.containsVehicle(swarm.selectedGroup, object)
                        enabled: !directory.identityConflict(object)
                        onToggled: if (!swarm.setMember(swarm.selectedGroup, object, checked))
                                       mainWindow.showMessageDialog(qsTr("Group membership"), qsTr("This vehicle is disconnected, ambiguous, or no longer available."))
                        Layout.fillWidth: true
                    }
                    QGCLabel {
                        text: directory.identityConflict(object) ? qsTr("IDENTITY CONFLICT") : (object.vehicleLinkManager.communicationLost ? qsTr("CONNECTION LOST") : qsTr("CONNECTED"))
                        color: directory.identityConflict(object) || object.vehicleLinkManager.communicationLost ? palette.colorRed : palette.colorGreen
                    }
                }
            }
        }
    }
}
