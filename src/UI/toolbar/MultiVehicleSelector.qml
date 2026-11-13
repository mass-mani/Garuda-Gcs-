/****************************************************************************
 *
 * (c) 2009-2020 QGROUNDCONTROL PROJECT <http://www.qgroundcontrol.org>
 *
 * QGroundControl is licensed according to the terms in the file
 * COPYING.md in the root of the source code directory.
 *
 ****************************************************************************/

import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

import QGroundControl
import QGroundControl.Controllers
import QGroundControl.FactSystem
import QGroundControl.FactControls
import QGroundControl.Controls
import QGroundControl.MultiVehicleManager
import QGroundControl.ScreenTools
import QGroundControl.Palette

RowLayout {
    id:         control
    spacing:    0

    property bool   showIndicator:        _multipleVehicles
    property var    _activeVehicle:       QGroundControl.multiVehicleManager.activeVehicle
    property bool   _multipleVehicles:    QGroundControl.multiVehicleManager.vehicles.count > 1
    GarudaVehicleDirectory { id: vehicleDirectory }

    RowLayout {
        Layout.fillWidth: true

        QGCColoredImage {
            width:      ScreenTools.defaultFontPixelWidth * 4
            height:     ScreenTools.defaultFontPixelHeight * 1.33
            fillMode:   Image.PreserveAspectFit
            mipmap:     true
            color:      qgcPal.text
            source:     "/InstrumentValueIcons/airplane.svg"
        }

        QGCLabel {
            text:               { vehicleDirectory.revision; return _activeVehicle ? vehicleDirectory.name(_activeVehicle) : qsTr("N/A") }
            font.pointSize:     ScreenTools.mediumFontPointSize
            Layout.alignment:   Qt.AlignCenter

            MouseArea {
                anchors.fill:   parent
                onClicked:      mainWindow.showIndicatorDrawer(vehicleSelectorDrawer, control)
            }
        }
    }

    Component {
        id: vehicleSelectorDrawer

        ToolIndicatorPage {
            showExpand: true

            contentComponent: Component {
                ColumnLayout {
                    spacing: ScreenTools.defaultFontPixelWidth / 2

                    Repeater {
                        model: QGroundControl.multiVehicleManager.vehicles

                        delegate: RowLayout {
                            required property var object
                            Layout.fillWidth: true
                            property bool editingName: false
                            QGCTextField {
                                id: vehicleName
                                Layout.fillWidth: true
                                maximumLength: 64
                                text: { vehicleDirectory.revision; object.vehicleUIDStr; return vehicleDirectory.name(object) }
                                readOnly: !parent.editingName
                                placeholderText: qsTr("Vehicle name")
                            }
                            QGCButton {
                                text: parent.editingName ? qsTr("Save") : qsTr("Rename")
                                onClicked: {
                                    if (parent.editingName) {
                                        if (!vehicleDirectory.rename(parent.object, vehicleName.text)) {
                                            mainWindow.showMessageDialog(qsTr("Vehicle name"), qsTr("Enter a name of 1–64 characters."))
                                            return
                                        }
                                        parent.editingName = false
                                    } else {
                                        parent.editingName = true
                                        vehicleName.forceActiveFocus()
                                    }
                                }
                            }
                            QGCButton {
                                text: parent.object === QGroundControl.multiVehicleManager.activeVehicle ? qsTr("Active · SYSID %1").arg(parent.object.id) : qsTr("Select · SYSID %1").arg(parent.object.id)
                                onClicked: {
                                    vehicleDirectory.select(parent.object)
                                    mainWindow.closeIndicatorDrawer()
                                }
                            }
                        }
                    }
                }
            }

            expandedComponent: Component {
                SettingsGroupLayout {
                    Layout.fillWidth: true

                    FactCheckBoxSlider {
                        Layout.fillWidth:   true
                        text:               qsTr("Enable Multi-Vehicle Panel")
                        fact:               _enableMultiVehiclePanel
                        visible:            _enableMultiVehiclePanel.visible

                        property Fact _enableMultiVehiclePanel: QGroundControl.settingsManager.appSettings.enableMultiVehiclePanel
                    }
                }
            }
        }
    }
}
