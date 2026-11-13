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
import QtQuick.Dialogs

import QGroundControl
import QGroundControl.Palette
import QGroundControl.Controls
import QGroundControl.FactControls
import QGroundControl.ScreenTools
import QGroundControl.Controllers

QGCPopupDialog {
    id:         root
    title:      qsTr("Edit Position")
    buttons:    Dialog.Close

    property alias coordinate:                  controller.coordinate
    property bool  showSetPositionFromVehicle:  true

    property real _margin:          ScreenTools.defaultFontPixelWidth / 2
    property real _textFieldWidth:  ScreenTools.defaultFontPixelWidth * 20
    property bool _showGeographic:  coordinateSystemCombo.comboBox.currentIndex === 0
    property bool _showUTM:         coordinateSystemCombo.comboBox.currentIndex === 1
    property bool _showMGRS:        coordinateSystemCombo.comboBox.currentIndex === 2
    property bool _showIndianGrid:  coordinateSystemCombo.comboBox.currentIndex === 3
    property bool _showVehicle:     coordinateSystemCombo.comboBox.currentIndex === 4
    property var  _indianGridResult: ({})

    IndianGridConverter { id: indianGridConverter }

    EditPositionDialogController {
        id: controller

        Component.onCompleted: initValues()
    }

    ColumnLayout {
        spacing: _margin

        LabelledComboBox {
            id:                 coordinateSystemCombo
            Layout.fillWidth:   true
            label:              qsTr("Coordinate System")
            model:              showSetPositionFromVehicle && globals.activeVehicle ? 
                                    [ qsTr("Geographic"), qsTr("Universal Transverse Mercator"), qsTr("Military Grid Reference"), qsTr("Indian Grid / IMGRS (inspection)"), qsTr("Vehicle Position") ] :
                                    [ qsTr("Geographic"), qsTr("Universal Transverse Mercator"), qsTr("Military Grid Reference"), qsTr("Indian Grid / IMGRS (inspection)") ]
        }

        LabelledFactTextField {
            label:              qsTr("Latitude")
            fact:               controller.latitude
            textFieldPreferredWidth: _textFieldWidth
            Layout.fillWidth:   true
            visible:            _showGeographic
        }

        LabelledFactTextField {
            label:              qsTr("Longitude")
            fact:               controller.longitude
            textFieldPreferredWidth: _textFieldWidth
            Layout.fillWidth:   true
            visible:            _showGeographic
        }

        LabelledButton {
            label:               qsTr("Set position")
            buttonText:          qsTr("Move")
            visible:             _showGeographic
            onClicked: {
                controller.setFromGeo()
                root.close()
            }
        }

        LabelledFactTextField {
            label:              qsTr("Zone")
            fact:               controller.zone
            textFieldPreferredWidth: _textFieldWidth
            Layout.fillWidth:   true
            visible:            _showUTM
        }

        LabelledFactComboBox {
            label:              qsTr("Hemisphere")
            fact:               controller.hemisphere
            indexModel:         false
            Layout.fillWidth:   true
            visible:            _showUTM
        }

        LabelledFactTextField {
            label:              qsTr("Easting")
            fact:               controller.easting
            textFieldPreferredWidth: _textFieldWidth
            Layout.fillWidth:   true
            visible:            _showUTM
        }

        LabelledFactTextField {
            label:              qsTr("Northing")
            fact:               controller.northing
            textFieldPreferredWidth: _textFieldWidth
            Layout.fillWidth:   true
            visible:            _showUTM
        }

        LabelledButton {
            label:               qsTr("Set position")
            buttonText:          qsTr("Move")
            visible:             _showUTM
            onClicked: {
                controller.setFromUTM()
                root.close()
            }
        }

        LabelledFactTextField {
            label:              qsTr("MGRS")
            fact:               controller.mgrs
            visible:            _showMGRS
            textFieldPreferredWidth: _textFieldWidth
            Layout.fillWidth:   true
        }

        LabelledButton {
            label:               qsTr("Set position")
            buttonText:          qsTr("Move")
            visible:             _showMGRS
            onClicked: {
                controller.setFromMGRS()
                root.close()
            }
        }

        QGCLabel {
            visible: _showIndianGrid
            Layout.fillWidth: true
            wrapMode: Text.WordWrap
            text: qsTr("Published Indian Grid parameters are approximate. This inspection converter cannot set a mission or vehicle position.")
        }
        QGCComboBox {
            id: indianZone
            visible: _showIndianGrid
            Layout.fillWidth: true
            model: [qsTr("Automatic zone"), "0", "IA", "IB", "IIA", "IIB", "IIIA", "IIIB", "IVA", "IVB"]
        }
        QGCButton {
            visible: _showIndianGrid
            text: qsTr("Convert current WGS84 position")
            onClicked: {
                var z = indianZone.currentIndex === 0 ? "" : indianZone.currentText
                _indianGridResult = indianGridConverter.fromWgs84(controller.coordinate.latitude, controller.coordinate.longitude, z)
                if (_indianGridResult.valid) {
                    indianEasting.text = _indianGridResult.easting.toFixed(1)
                    indianNorthing.text = _indianGridResult.northing.toFixed(1)
                }
            }
        }
        QGCTextField {
            id: indianEasting
            visible: _showIndianGrid
            Layout.fillWidth: true
            placeholderText: qsTr("Full easting (metres)")
            inputMethodHints: Qt.ImhFormattedNumbersOnly
        }
        QGCTextField {
            id: indianNorthing
            visible: _showIndianGrid
            Layout.fillWidth: true
            placeholderText: qsTr("Full northing (metres)")
            inputMethodHints: Qt.ImhFormattedNumbersOnly
        }
        QGCButton {
            visible: _showIndianGrid
            text: qsTr("Convert grid to WGS84 (inspection only)")
            onClicked: {
                if (indianZone.currentIndex === 0) {
                    _indianGridResult = { valid: false, error: qsTr("Select an explicit zone for inverse conversion") }
                } else if (!indianEasting.text.trim() || !indianNorthing.text.trim()) {
                    _indianGridResult = { valid: false, error: qsTr("Enter full easting and northing") }
                } else {
                    _indianGridResult = indianGridConverter.toWgs84(indianZone.currentText, Number(indianEasting.text), Number(indianNorthing.text))
                }
            }
        }
        QGCLabel {
            visible: _showIndianGrid
            Layout.fillWidth: true
            wrapMode: Text.WordWrap
            text: _indianGridResult.valid ?
                      (_indianGridResult.zone + "  E " + _indianGridResult.easting.toFixed(1) +
                       " m  N " + _indianGridResult.northing.toFixed(1) + " m\n" +
                       "WGS84 " + _indianGridResult.latitude.toFixed(7) + ", " + _indianGridResult.longitude.toFixed(7) +
                       "\n" + _indianGridResult.warning) :
                      (_indianGridResult.error || "")
        }

        LabelledButton {
            label:               qsTr("Set position")
            buttonText:          qsTr("Move")
            visible:             _showVehicle
            onClicked: {
                controller.setFromVehicle()
                root.close()
            }
        }
    }
}
