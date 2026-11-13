import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QGroundControl
import QGroundControl.Controls
import QGroundControl.ScreenTools

Item {
    id: root
    required property var guidedController
    signal homeAltitudeToggled()
    property var vehicle: QGroundControl.multiVehicleManager.activeVehicle
    readonly property bool live: vehicle !== null && !vehicle.vehicleLinkManager.communicationLost
    function value(fact, digits) {
        return live && fact && isFinite(Number(fact.rawValue)) ? Number(fact.rawValue).toFixed(digits) : "—"
    }
    TabBar {
        id: tabs; anchors.top: parent.top; width: parent.width
        TabButton { text: qsTr("Quick") }
        TabButton { text: qsTr("Actions") }
        TabButton { text: qsTr("Messages") }
        TabButton { text: qsTr("Status") }
    }
    StackLayout {
        anchors.top: tabs.bottom; anchors.bottom: parent.bottom
        anchors.left: parent.left; anchors.right: parent.right
        currentIndex: tabs.currentIndex
        GridLayout {
            columns: 2; rowSpacing: 1; columnSpacing: 4
            Repeater {
                model: [
                    {label:qsTr("Altitude (m)"),fact:root.vehicle?root.vehicle.altitudeRelative:null,color:"#d9b4ed"},
                    {label:qsTr("Groundspeed (m/s)"),fact:root.vehicle?root.vehicle.groundSpeed:null,color:"#e8b083"},
                    {label:qsTr("Distance to WP (m)"),fact:root.vehicle?root.vehicle.distanceToNextWP:null,color:"#efaaaa"},
                    {label:qsTr("Yaw (°)"),fact:root.vehicle?root.vehicle.heading:null,color:"#8ae7a6"},
                    {label:qsTr("Climb (m/s)"),fact:root.vehicle?root.vehicle.climbRate:null,color:"#e9eb97"},
                    {label:qsTr("Distance to GCS (m)"),fact:root.vehicle?root.vehicle.distanceToGCS:null,color:"#8ce9ef"}
                ]
                delegate: Rectangle {
                    required property var modelData
                    Layout.fillWidth: true; Layout.fillHeight: true
                    color: "#252929"; radius: 3
                    Column {
                        anchors.centerIn: parent; width: parent.width
                        QGCLabel { width:parent.width; horizontalAlignment:Text.AlignHCenter; text:modelData.label; font.pixelSize:Math.max(10,root.width*.031); color:"#e0e5e9" }
                        Text { width:parent.width; horizontalAlignment:Text.AlignHCenter; text:root.value(modelData.fact,2); color:modelData.color; font.pixelSize:Math.min(root.width*.105,Math.max(18,parent.parent.height*.52)); fontSizeMode:Text.HorizontalFit; minimumPixelSize:12 }
                    }
                }
            }
        }
        GarudaFlightActions {
            guidedController:root.guidedController
            onHomeAltitudeToggled:root.homeAltitudeToggled()
        }
        ScrollView {
            clip:true
            TextArea {
                readOnly:true; selectByMouse:true; wrapMode:TextEdit.Wrap
                textFormat:TextEdit.RichText; color:"#e0e5e9"
                text:root.vehicle && root.vehicle.formattedMessages.length ? root.vehicle.formattedMessages : qsTr("No vehicle messages.")
                background:Rectangle { color:"#142330" }
            }
        }
        ScrollView {
            clip:true
            ColumnLayout {
                width:parent.width
                Repeater {
                    model: [
                        [qsTr("Link"),root.live ? qsTr("Connected") : qsTr("No telemetry")],
                        [qsTr("Mode"),root.live ? root.vehicle.flightMode : "—"],
                        [qsTr("Arming"),root.live ? (root.vehicle.armed ? qsTr("Armed") : qsTr("Disarmed")) : "—"],
                        [qsTr("GPS fix"),root.live ? root.vehicle.gps.lock.enumOrValueString : "—"],
                        [qsTr("Satellites"),root.live ? root.vehicle.gps.count.valueString : "—"],
                        [qsTr("HDOP"),root.value(root.vehicle?root.vehicle.gps.hdop:null,2)],
                        [qsTr("Latitude"),root.value(root.vehicle?root.vehicle.gps.lat:null,7)],
                        [qsTr("Longitude"),root.value(root.vehicle?root.vehicle.gps.lon:null,7)],
                        [qsTr("Received / lost"),root.live ? root.vehicle.messagesReceived+" / "+root.vehicle.messagesLost : "—"]
                    ]
                    delegate:RowLayout {
                        required property var modelData
                        Layout.fillWidth:true
                        QGCLabel { Layout.fillWidth:true; text:modelData[0] }
                        QGCLabel { text:modelData[1]; color:"#a3d9f2" }
                    }
                }
                QGCLabel { Layout.fillWidth:true; wrapMode:Text.WordWrap; color:"#ffbd75"; text:root.live ? root.vehicle.prearmError : qsTr("Live values appear when a vehicle connects.") }
            }
        }
    }
}
