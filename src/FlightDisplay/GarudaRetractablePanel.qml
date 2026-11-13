import QtQuick
import QtQuick.Layouts
import QGroundControl
import QGroundControl.Controls
import QGroundControl.ScreenTools
Rectangle {
    id: root
    property string title
    property string settingsKey
    property bool expanded: true
    property real expandedHeight: 250
    property Component panelContent
    property bool canPopOut: false
    signal popOutRequested()
    readonly property real headerHeight: ScreenTools.defaultFontPixelHeight * 2.1
    height: expanded ? expandedHeight : headerHeight
    color: "#ed071521"; border.color: "#526677"; radius: 4; clip: true
    Component.onCompleted: expanded = QGroundControl.loadBoolGlobalSetting(settingsKey, true)
    Behavior on height { NumberAnimation { duration: 160 } }
    MouseArea { anchors.fill: parent; onWheel: function(wheel) { wheel.accepted = true } }
    RowLayout {
        id: header
        anchors.top: parent.top; anchors.left: parent.left; anchors.right: parent.right
        height: root.headerHeight; spacing: 2
        QGCButton {
            Layout.fillWidth: true; Layout.fillHeight: true
            text: (root.expanded ? "▾  " : "▸  ") + root.title
            onClicked: {
                root.expanded = !root.expanded
                QGroundControl.saveBoolGlobalSetting(root.settingsKey, root.expanded)
            }
        }
        QGCButton {
            visible: root.canPopOut; text: qsTr("Pop out"); Layout.fillHeight: true
            onClicked: root.popOutRequested()
        }
    }
    Loader {
        anchors.top: header.bottom; anchors.bottom: parent.bottom
        anchors.left: parent.left; anchors.right: parent.right; anchors.margins: 4
        visible: root.expanded; sourceComponent: root.panelContent
    }
}
