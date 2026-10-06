from setuptools import find_packages, setup

package_name = 'haptic_band_ros'

setup(
    name=package_name,
    version='0.0.0',
    packages=find_packages(exclude=['test']),
    data_files=[
        ('share/ament_index/resource_index/packages',
            ['resource/' + package_name]),
        ('share/' + package_name, ['package.xml']),
    ],
    install_requires=['setuptools'],
    zip_safe=True,
    maintainer='gilbertsoco',
    maintainer_email='gilsocojp@gmail.com',
    description='Bridge from a ROS 2 topic to the haptic wristband',
    license='MIT',
    extras_require={
        'test': [
            'pytest',
        ],
    },
    entry_points={
        'console_scripts': [
            'haptic_bridge = haptic_band_ros.haptic_bridge:main',
            'haptic_gui = haptic_band_ros.gui.main_app:main'
        ],
    },
)
