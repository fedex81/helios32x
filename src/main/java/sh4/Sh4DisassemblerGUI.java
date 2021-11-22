package sh4;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JSeparator;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListModel;

import javax.swing.WindowConstants;
import javax.swing.border.BevelBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableModel;
import javax.swing.SwingUtilities;


/**
* This code was edited or generated using CloudGarden's Jigloo
* SWT/Swing GUI Builder, which is free for non-commercial
* use. If Jigloo is being used commercially (ie, by a corporation,
* company or business for any purpose whatever) then you
* should purchase a license for each developer using Jigloo.
* Please visit www.cloudgarden.com for details.
* Use of Jigloo implies acceptance of these licensing terms.
* A COMMERCIAL LICENSE HAS NOT BEEN PURCHASED FOR
* THIS MACHINE, SO JIGLOO OR THIS CODE CANNOT BE USED
* LEGALLY FOR ANY CORPORATE OR COMMERCIAL PURPOSE.
*/
public class Sh4DisassemblerGUI extends javax.swing.JFrame {
	private JLabel codeLabel;
	private JList disassemblerList;
	private JLabel fpscrReg;
	private JButton closeButton;
	private JLabel vbrReg;
	private JButton stepButton;
	private JLabel srRegister;
	private JTable floatRegisterTable;
	private JLabel floatRegisters;
	private JTable RegisterTable;
	private JLabel Registers;
	private JSeparator Separator;
	private JTextField jumpTextField;
	private JButton gotoButton;
	private JButton stopButton;
	private ButtonGroup disassemblerControlGroup;

	{
		//Set Look & Feel
		try {
			javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
		} catch(Exception e) {
			e.printStackTrace();
		}
	}


	public Sh4DisassemblerGUI() {
		super();
		initGUI();
	}
	
	private void initGUI() {
		try {
			setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
			getContentPane().setLayout(null);
			this.setTitle("Java Sh4 Disassembler");
			{
				codeLabel = new JLabel();
				getContentPane().add(codeLabel);
				codeLabel.setText("Disassembled Code");
				codeLabel.setFont(new java.awt.Font("Andale Mono",1,12));
				codeLabel.setBounds(5, 0, 140, 26);
			}
			{
				ListModel disassemblerListModel = 
					new DefaultComboBoxModel(
							new String[] { "Item One", "Item Two" });
				disassemblerList = new JList();
				getContentPane().add(disassemblerList);
				disassemblerList.setModel(disassemblerListModel);
				disassemblerList.setBounds(5, 24, 263, 415);
				disassemblerList.setBorder(BorderFactory.createBevelBorder(BevelBorder.LOWERED));
			}
			{
				stepButton = new JButton();
				getContentPane().add(stepButton);
				stepButton.setText("Step");
				stepButton.setBounds(289, 26, 89, 25);
				stepButton.setFont(new java.awt.Font("Andale Mono",1,12));
			}
			{
				stopButton = new JButton();
				getContentPane().add(stopButton);
				stopButton.setText("Stop");
				stopButton.setBounds(403, 26, 91, 25);
				stopButton.setFont(new java.awt.Font("Andale Mono",1,12));
			}
			{
				gotoButton = new JButton();
				getContentPane().add(gotoButton);
				gotoButton.setText("Goto");
				gotoButton.setBounds(421, 68, 97, 25);
				gotoButton.setFont(new java.awt.Font("Andale Mono",1,12));
			}
			{
				jumpTextField = new JTextField();
				getContentPane().add(jumpTextField);
				jumpTextField.setBounds(286, 71, 129, 19);
			}
			{
				Separator = new JSeparator();
				getContentPane().add(Separator);
				Separator.setBounds(274, 115, 258, 10);
			}
			{
				Registers = new JLabel();
				getContentPane().add(Registers);
				Registers.setText("GPR Register Output");
				Registers.setBounds(286, 131, 227, 15);
				Registers.setFont(new java.awt.Font("Andale Mono",1,12));
			}
			{
				TableModel RegisterTableModel = 
					new DefaultTableModel(
							new String[][] { { "One", "Two" }, { "Three", "Four" } },
							new String[] { "Column 1", "Column 2" });
				RegisterTable = new JTable(8,2);
				getContentPane().add(RegisterTable);
				RegisterTable.setModel(RegisterTableModel);
				RegisterTable.setBounds(280, 152, 252, 123);
			}
			{
				floatRegisters = new JLabel();
				getContentPane().add(floatRegisters);
				floatRegisters.setText("Floating Point Registers");
				floatRegisters.setBounds(280, 300, 220, 15);
				floatRegisters.setFont(new java.awt.Font("Andale Mono",1,12));
			}
			{
				TableModel floatRegisterTableModel = 
					new DefaultTableModel(
							new String[][] { { "One", "Two" }, { "Three", "Four" } },
							new String[] { "Column 1", "Column 2" });
				floatRegisterTable = new JTable(8,2);
				getContentPane().add(floatRegisterTable);
				floatRegisterTable.setModel(floatRegisterTableModel);
				floatRegisterTable.setBounds(280, 321, 252, 119);
			}
			{
				srRegister = new JLabel();
				getContentPane().add(srRegister);
				srRegister.setText("SR: ");
				srRegister.setBounds(12, 451, 36, 15);
				srRegister.setFont(new java.awt.Font("Andale Mono",1,12));
			}
			{
				fpscrReg = new JLabel();
				getContentPane().add(fpscrReg);
				fpscrReg.setText("FPSCR:");
				fpscrReg.setBounds(12, 472, 52, 15);
				fpscrReg.setFont(new java.awt.Font("Andale Mono",1,12));
			}
			{
				vbrReg = new JLabel();
				getContentPane().add(vbrReg);
				vbrReg.setText("VBR:");
				vbrReg.setBounds(189, 451, 47, 15);
				vbrReg.setFont(new java.awt.Font("Andale Mono",1,12));
			}
			{
				closeButton = new JButton();
				getContentPane().add(closeButton);
				closeButton.setText("Close");
				closeButton.setBounds(463, 472, 69, 25);
			}
			pack();
			this.setSize(583, 543);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	private ButtonGroup getDisassemblerControlGroup() {
		if(disassemblerControlGroup == null) {
			disassemblerControlGroup = new ButtonGroup();
		}
		return disassemblerControlGroup;
	}

}
