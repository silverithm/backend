package com.silverithm.vehicleplacementsystem.service;

import com.silverithm.vehicleplacementsystem.dto.AddEmployeeRequest;
import com.silverithm.vehicleplacementsystem.dto.EmployeeDTO;
import com.silverithm.vehicleplacementsystem.dto.EmployeeUpdateRequestDTO;
import com.silverithm.vehicleplacementsystem.dto.Location;
import com.silverithm.vehicleplacementsystem.entity.AppUser;
import com.silverithm.vehicleplacementsystem.entity.Employee;
import com.silverithm.vehicleplacementsystem.repository.EmployeeRepository;
import com.silverithm.vehicleplacementsystem.repository.UserRepository;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class EmployeeService {

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GeocodingService geocodingService;

    public void addEmployee(Long userId, AddEmployeeRequest addEmployeeRequest) throws Exception {

        Location homeAddress = geocodingService.getAddressCoordinates(addEmployeeRequest.homeAddress());
        Location workPlace = geocodingService.getAddressCoordinates(addEmployeeRequest.workPlace());

        if (homeAddress == null || workPlace == null) {
            throw new Exception();
        }

        System.out.println(homeAddress + " " + addEmployeeRequest.homeAddress());
        System.out.println(workPlace + " " + addEmployeeRequest.workPlace());

        AppUser user = userRepository.findById(userId).orElseThrow();

        Employee employee = new Employee(addEmployeeRequest.workPlace(), addEmployeeRequest.homeAddress(),
                addEmployeeRequest.name(), workPlace, homeAddress,
                addEmployeeRequest.maxCapacity(), user);
        employeeRepository.save(employee);
    }

    public List<EmployeeDTO> getEmployees(Long userId) {
        log.info(String.valueOf(userRepository.findAll().size()));
        for (AppUser user : userRepository.findAll()) {
            log.info(user.getId().toString() + " " + user.getUsername().toString());
        }
        List<Employee> employees = employeeRepository.findByUserId(userId);

        List<EmployeeDTO> employeeDTOS = employees.stream()
                .map(employee -> new EmployeeDTO(employee.getId(), employee.getHomeAddressName(),
                        employee.getWorkPlaceAddressName(), employee.getName(), employee.getHomeAddress(),
                        employee.getWorkPlace(),
                        employee.getMaximumCapacity())).collect(Collectors.toList());

        return employeeDTOS;

    }

    public List<EmployeeDTO> getEmployees() {
        List<Employee> employees = employeeRepository.findAll();

        List<EmployeeDTO> employeeDTOS = employees.stream()
                .map(employee -> new EmployeeDTO(employee.getId(), employee.getHomeAddressName(),
                        employee.getWorkPlaceAddressName(), employee.getName(), employee.getHomeAddress(),
                        employee.getWorkPlace(),
                        employee.getMaximumCapacity())).collect(Collectors.toList());

        return employeeDTOS;

    }

    public void deleteEmployee(Long id) {
        employeeRepository.deleteById(id);
    }

    public Workbook downloadExcel() {
        Workbook workbook = new XSSFWorkbook();
        Sheet employeeSheet = workbook.createSheet("직원");
        int rowNo = 0;

        Row headerRow = employeeSheet.createRow(rowNo++);
        headerRow.createCell(0).setCellValue("아이디");
        headerRow.createCell(1).setCellValue("이름");
        headerRow.createCell(2).setCellValue("집주소");
        headerRow.createCell(3).setCellValue("직장주소");
        headerRow.createCell(4).setCellValue("최대인원");

        List<Employee> employees = employeeRepository.findAll();

        for (Employee employee : employees) {

            Row employeeRow = employeeSheet.createRow(rowNo++);
            employeeRow.createCell(0).setCellValue(employee.getId());
            employeeRow.createCell(1).setCellValue(employee.getName());
            employeeRow.createCell(2).setCellValue(employee.getHomeAddressName());
            employeeRow.createCell(3).setCellValue(employee.getWorkPlaceAddressName());
            employeeRow.createCell(4).setCellValue(employee.getMaximumCapacity());

        }

        return workbook;
    }


    @Transactional
    public void updateEmployee(Long id, EmployeeUpdateRequestDTO employeeUpdateRequestDTO) throws Exception {

        Location updatedHomeAddress = geocodingService.getAddressCoordinates(employeeUpdateRequestDTO.homeAddress());
        Location updatedWorkPlace = geocodingService.getAddressCoordinates(employeeUpdateRequestDTO.workPlace());

        Employee employee = employeeRepository.findById(id).orElseThrow();
        employee.update(employeeUpdateRequestDTO.homeAddress(), employeeUpdateRequestDTO.workPlace(),
                employeeUpdateRequestDTO.name(), updatedHomeAddress,
                updatedWorkPlace, employeeUpdateRequestDTO.maxCapacity());
    }

    @Transactional
    public void uploadExcel(InputStream file) throws Exception {

        Workbook workbook = new XSSFWorkbook(file);

        for (Sheet sheet : workbook) {

            for (int i = 1; i < sheet.getPhysicalNumberOfRows(); i++) {

                double idCell = sheet.getRow(i).getCell(0).getNumericCellValue();
                Long id = (long) idCell;

                String name = "";
                if (sheet.getRow(i).getCell(1) != null) {
                    name = sheet.getRow(i).getCell(1).getStringCellValue();
                }
                log.info(name);

                String homeAddressName = "";
                if (sheet.getRow(i).getCell(2) != null) {
                    homeAddressName = sheet.getRow(i).getCell(2).getStringCellValue();
                }
                log.info(homeAddressName);

                String workPlaceName = "";
                if (sheet.getRow(i).getCell(3) != null) {
                    workPlaceName = "경상남도 진주시 주약약골길 86";
                }

                int maximumCapacity = 0;
                if (sheet.getRow(i).getCell(4) != null) {
                    maximumCapacity = (int) sheet.getRow(i).getCell(4).getNumericCellValue();
                }
                log.info(String.valueOf(maximumCapacity));

                if (id.equals(0)) {
                    // create
                    this.addEmployee(1L, new AddEmployeeRequest(
                            name, workPlaceName, homeAddressName, maximumCapacity, id
                    ));
                } else if (homeAddressName != "" && workPlaceName != "") {
                    // update
                    this.updateEmployee(1L,
                            new EmployeeUpdateRequestDTO(name, homeAddressName, workPlaceName, maximumCapacity));
                }

            }

//            for (Row row : sheet) {
//                double idCell = row.getCell(0).getNumericCellValue();
//                Long id = (long) idCell;
//                String name = row.getCell(1).getStringCellValue();
//                String homeAddressName = row.getCell(2).getStringCellValue();
//                String workPlaceName = "경상남도 진주시 주약약골길 86";
//                int maximumCapacity = (int) row.getCell(3).getNumericCellValue();
//
//                if (id.equals(0)) {
//                    // create
//
//                    this.addEmployee(1L, new AddEmployeeRequest(
//                            name, workPlaceName, homeAddressName, maximumCapacity, id
//                    ));
//
//                } else {
//                    // update
//                    this.updateEmployee(1L,
//                            new EmployeeUpdateRequestDTO(name, homeAddressName, workPlaceName, maximumCapacity));
//                }
//
//            }
        }
    }


}
