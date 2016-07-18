
//===========================================================================
//
//  This program use Eclipse JDT to parse java source files 
//  and dumps resulting AST in JSON representation.
//
//---------------------------------------------------------------------------
//
//  Copyright (C) 2015
//  by Oleg Mazko(o.mazko@mail.ru).
//
//  The author gives unlimited permission to copy and distribute
//  this file, with or without modifications, as long as this notice
//  is preserved, and any changes are properly documented.
//

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Comment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.InfixExpression;

public class App {
	static String readFile(final String path, final Charset encoding) throws IOException {
		final byte[] encoded = Files.readAllBytes(Paths.get(path));
		return new String(encoded, encoding);
	}

	public static void main(final String[] args) throws Exception {
		final Options options = new Options().addOption("c", "attach comments").addOption("h", "print this message");
		final CommandLine cmd = new DefaultParser().parse(options, args);
		if (args.length == 0 || cmd.hasOption("h")) {
			new HelpFormatter().printHelp(App.class.getSimpleName() + "[OPTION]... [FILE]...", options);
		} else {
			String inputFile = cmd.getArgs()[0];
			String outputDirectory = cmd.getArgs()[1];
			File file = new File(inputFile);
			if (file.isDirectory()) {
				for (File sourceFile : App.getDirectorySourceFiles(file)) {
					ast(
						sourceFile.getPath(),
						Paths.get(outputDirectory, Paths.get(inputFile).relativize(sourceFile.toPath()).toString()).toString(),
						cmd
					);
				}
			} else if (file.isFile()) {
				ast(
					inputFile,
					Paths.get(outputDirectory, Paths.get(inputFile).getFileName().toString()).toString(),
					cmd
				);
			}
		}
	}

	/**
	* Recursively finds and returns all source files in a directory
	*
	* @param directory The directory to search
	* @return The set of source files found in the directory
	*/
	protected static Set<File> getDirectorySourceFiles(File directory) {
		Set<File> sourceFiles = new HashSet<File>();

		if (directory.isDirectory() && directory.canRead()) {
			File[] files = directory.listFiles();

			if (files != null) {
				for(File file : files) {
					if (file.isDirectory()) {
						sourceFiles.addAll(getDirectorySourceFiles(file));
					} else {
						if (file.getName().endsWith(".java")) {
							sourceFiles.add(file);
						}
					}
				}
			}
		}

		return sourceFiles;
	}

	private static void ast(final String file, final String outputFile, final CommandLine cmd) throws IOException {
		@SuppressWarnings("deprecation")
		final ASTParser parser = ASTParser.newParser(AST.JLS8);
		final String src = readFile(file, StandardCharsets.UTF_8);
		parser.setSource(src.toCharArray());
		parser.setKind(ASTParser.K_COMPILATION_UNIT);
		final Map<?, ?> options = JavaCore.getOptions();
		JavaCore.setComplianceOptions(JavaCore.VERSION_1_8, options);
		parser.setCompilerOptions(options);
		final CompilationUnit cu = (CompilationUnit) parser.createAST(null);
		for (IProblem problem : cu.getProblems()) {
			System.err.println(problem);
		}
		cu.accept(new ASTVisitor() {
			// Eclipse AST optimize deeply nested expressions of the form L op R
			// op R2
			// op R3... where the same operator appears between
			// all the operands. This
			// function disable such optimization back to tree view.
			@Override
			public boolean visit(final InfixExpression node) {
				if (node.hasExtendedOperands()) {
					@SuppressWarnings("unchecked")
					List<Expression> operands = new ArrayList<>(node.extendedOperands());
					Collections.reverse(operands);
					operands.add(node.getRightOperand());
					final Expression firstOperand = operands.remove(0);
					firstOperand.delete(); // remove node from its parent
					node.setRightOperand(firstOperand);
					InfixExpression last = node;
					for (final Expression expr : operands) {
						InfixExpression infixExpression = node.getAST().newInfixExpression();
						infixExpression.setOperator(node.getOperator());
						expr.delete();
						infixExpression.setRightOperand(expr);
						final Expression left = last.getLeftOperand();
						last.setLeftOperand(infixExpression);
						infixExpression.setLeftOperand(left);
						last = infixExpression;
					}
				}

				return super.visit(node);
			}
		});

		// Create the file to connect to
		File output = new File(outputFile.replaceFirst("\\.java$", ".json"));
		output.getParentFile().mkdirs();
		output.createNewFile();
		FileOutputStream outputStream = new FileOutputStream(output, false);

		if (cmd.hasOption("c")) {
			final ASTDumper dumper = new ASTDumper(new JSONStyleASTPrinter(outputStream), null);
			dumper.dump(cu);
			System.out.flush();
		} else {
			final ASTDumper dumper = new ASTDumper(new JSONStyleASTPrinter(outputStream), null);
			for (final Object comment : cu.getCommentList()) {
				((Comment) comment).delete();
			}
			dumper.dump(cu);
		}
	}
}
