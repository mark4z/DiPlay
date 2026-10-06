# CarPlay return-to-home artwork

`tesla-carplay.svg` is the editable source for
`common/src/main/res/raw/ic_car_home.png`. The PNG is a 192×192, 8-bit RGB,
opaque square: white Tesla T on red, with 32-pixel padding. It replaces the
existing default OEM-entry artwork, using the original PNG transport and car
button settings. The default label is **Tesla**; saved labels and custom images
keep precedence. Choosing the default image restores this artwork. Reconnect
CarPlay after changing the label or image.

The button retains its original action: open Android's home screen while the
CarPlay session stays in the background. The app name, receiver identity,
launcher icon, image picker and settings UI are unchanged. This is cosmetic
branding and does not provide Tesla vehicle integration.

The T path and red `#CC0000` come from [Simple Icons' Tesla icon](https://github.com/simple-icons/simple-icons/blob/98820a4dc8c363ca72fa2c0d294ea4a0a9bba75d/icons/tesla.svg),
pinned to revision `98820a4dc8c363ca72fa2c0d294ea4a0a9bba75d`. Its
[metadata](https://github.com/simple-icons/simple-icons/blob/98820a4dc8c363ca72fa2c0d294ea4a0a9bba75d/data/simple-icons.json)
identifies Tesla's gallery as the original source. No Tesla gallery download or
media-use agreement is used here. The path is unchanged; only scale, position,
white fill and a red background were applied.

Simple Icons distributes its project under [CC0](https://github.com/simple-icons/simple-icons/blob/98820a4dc8c363ca72fa2c0d294ea4a0a9bba75d/LICENSE.md),
but its [disclaimer](https://github.com/simple-icons/simple-icons/blob/98820a4dc8c363ca72fa2c0d294ea4a0a9bba75d/DISCLAIMER.md)
explains that this does not grant rights to every underlying brand mark. Tesla's
name and logo remain their owner's property; this notice does not grant
trademark rights. Review applicable rights before distributing a branded build.
DiPlay is not affiliated with or endorsed by Tesla.

To regenerate with Inkscape and ImageMagick from the repository root:

```sh
inkscape asset/carplay/tesla-carplay.svg --export-filename=/tmp/tesla-carplay.png \
  --export-width=192 --export-height=192
magick /tmp/tesla-carplay.png -strip -alpha off -define png:color-type=2 \
  common/src/main/res/raw/ic_car_home.png
```

`OemCarPlayBrandingTest` covers the default `/info` entry, the packaged image,
saved overrides and the original car-button controls.
