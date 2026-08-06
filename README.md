# DecisionTable

## XML Schema

### `SystemConfiguration`

```xml
<SystemConfiguration name="CompetitionRobot" enabled="true">
    <States>
        <State name="ClawState">
            <Value name="OPEN"/>
            <Value name="CLOSED"/>
        </State>
    </States>

    <Devices>
        <Device driver="MecanumDrive" name="Drivetrain">
            <Parameter name="param:FrontLeft" value="drivetrainMotorFrontLeft">
                <Parameter name="param:Direction" value="REVERSE"/>
            </Parameter>
            <Parameter name="param:FrontRight" value="drivetrainMotorFrontRight">
                <Parameter name="param:Direction" value="FORWARD"/>
            </Parameter>
            <Parameter name="param:BackLeft" value="drivetrainMotorBackLeft">
                <Parameter name="param:Direction" value="REVERSE"/>
            </Parameter>
            <Parameter name="param:BackRight" value="drivetrainMotorBackRight">
                <Parameter name="param:Direction" value="FORWARD"/>
            </Parameter>
        </Device>
      
        <Device driver="Servo" name="ClawServo">
            <Parameter name="param:HardwareMap" value="claw_servo">
                <Parameter name="param:Direction" value="FORWARD"/>
            </Parameter>
        </Device>

        <Device driver="Gamepad" name="DriverOne">
            <Parameter name="param:ID" value="1"/>
        </Device>
    </Devices>

    <InternalVariables>
        <VariableGroup name="Drivetrain">
            <Variable name="SlowScale" type="Float" value="0.6" description="Scale while the drivers right bumper is held"/>
            <Variable name="FullScale" type="Float" value="1.0" description="Scale otherwise"/>
        </VariableGroup>
      
        <VariableGroup name="Claw">
            <Variable name="State" type="ClawState" value="OPEN" description="The current claw position"/>
            <Variable name="OpenPosition" type="Double" value="0.8"/>
            <Variable name="ClosedPosition" type="Double" value="0.2"/>
        </VariableGroup>
    </InternalVariables>
</SystemConfiguration>
```

- **`<Devices>`**: physical robot hardware in the hardware map with the name of a driver (`Servo`, `Motor`, `Gamepad`, `MecanumDrive`..., and its parameters.
  - Parameters support the use of the `Enum` type, which is a string that must match one of the enum values of the parameter's type. For example, `Direction` is an enum with values `FORWARD` and `REVERSE`.
- **`<InternalVariables>`**: Values that live outside the scope of a device. Types are `Boolean`, `Double`, `Float`, `Integer`, `String`, or the name of a `<State>`.
  - TBD: Custom types can be defined in the future using the `ValueTypeParser` interface.
- **`<States>`**: Exist for expressing state machines.

### `DecisionTable`

```xml
<DecisionTable name="TeleOp" enabled="true" type="TELEOP" systemConfigurationFile="CompetitionRobot" transitionTarget="">
    <Rules>
        <RuleGroup name="Drivetrain">
            <Rule name="Drive" priority="0">
                <Condition>
                    <Always/>
                </Condition>
                <Action>
                    <Set target="param:Drivetrain.X" ref="param:DriverOne.Joystick.LeftStickX"/>
                    <Set target="param:Drivetrain.Y" ref="param:DriverOne.Joystick.LeftStickY"/>
                    <Set target="param:Drivetrain.RX" ref="param:DriverOne.Joystick.RightStickX"/>
                </Action>
            </Rule>

            <Rule name="Slow mode when Driver Ones right bumper is pressed" priority="1">
                <Condition>
                    <And>
                        <Equals target="param:DriverOne.Button.RightBumper" value="true"/>
                        <NotEquals target="prev:DriverOne.Button.RightBumper" value="true"/>
                    </And>
                </Condition>
                <Action>
                    <Set target="param:Drivetrain.Scale" ref="var:Drivetrain.SlowScale"/>
                </Action>
            </Rule>

            <Rule name="Full speed when Driver Ones right bumper is released" priority="1">
                <Condition>
                    <And>
                        <Equals target="param:DriverOne.Button.RightBumper" value="false"/>
                        <Equals target="prev:DriverOne.Button.RightBumper" value="true"/>
                    </And>
                </Condition>
                <Action>
                    <Set target="param:Drivetrain.Scale" ref="var:Drivetrain.FullScale"/>
                </Action>
            </Rule>
        </RuleGroup>
      
        <RuleGroup name="Claw">
            <Rule name="Open claw on A press" priority="1">
                <Condition>
                    <And>
                        <Equals target="Driver.Button.A" value="true"/>
                        <NotEquals target="prev:Driver.Button.A" value="true"/>
                        <Equals target="var:Claw.State" value="CLOSED"/>
                    </And>
                </Condition>
                <Action>
                    <Set target="var:Claw.State" value="OPEN"/>
                </Action>
            </Rule>

            <Rule name="Apply claw position from state" priority="2">
                <Condition>
                    <Equals target="var:Claw.State" value="OPEN"/>
                </Condition>
                <Action>
                    <Set target="param:ClawServo.Position" ref="var:Claw.OpenPosition"/>
                </Action>
            </Rule>
        </RuleGroup>
    </Rules>
</DecisionTable>
```

- **`type`** is `TELEOP`, `AUTONOMOUS`, or `UTILITY`
- **`<RuleGroup>`**: Organize all related rules together (ex. by subsystem). A group can be disabled with `enabled="false"`.
- **`<Rule>`**: A single rule with a `name`, `priority`, and a `<Condition>` and `<Action>`. A rule can be disabled with `enabled="false"`.
- **`<Condition>`**: a boolean expression: `Equals`, `NotEquals`, `GreaterThan`, `LessThan`, `GreaterOrEqual`, `LessOrEqual`, `And`, `Or`, `XOr`, `Not`, `Always`, and `Once`.
- **`<Action>`** is one or more `<Set>`s, each taking a `value="..."` or a `ref="..."` to another address.

## Addresses

Addresses serve as a way to reference a value in the system configuration or decision table in a way that both Java and the XML can understand. The following address types are supported:

| Prefix                   | Resolves to                                           |
|--------------------------|-------------------------------------------------------|
| `param:Device.Parameter` | A device's parameter (readable and writable).         |
| `prev:Device.Parameter`  | A parameter's value as of the last loop. (Read only). |
| `var:Group.Name`         | An internal variable (Readable and writable).         |

Addresses are useful for referencing values in conditions and actions, and can be used to create complex logic in decision tables. They exist to help bridge the gap between the XML and Java code, allowing for a more declarative approach to robot programming.

## Built-in drivers

The following drivers are provided by the `FTC` module (SUBJECT TO CHANGE):

- **Raw hardware**: `Servo`, `CRServo`, `Motor`, `Gamepad`, `IMU`, `ColorSensor`, `DistanceSensor`, `TouchSensor`,
  `DigitalChannel`, `AnalogInput`, `VoltageSensor`
- **Common code**: `MecanumDrive`, `TankDrive`, `PIDF`, `Timer`, `Clock`, `GoBildaHeadlight`

Custom drivers can be created by implementing the `Driver` interface, and will be automatically discovered.
- For devices that need to update their state every loop, implement the `UpdatableDevice` interface.